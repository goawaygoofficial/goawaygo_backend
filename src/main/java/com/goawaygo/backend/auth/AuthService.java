package com.goawaygo.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goawaygo.backend.auth.AccessTokenService.IssuedToken;
import com.goawaygo.backend.auth.RefreshTokenService.IssuedRefreshToken;
import com.goawaygo.backend.auth.dto.SigninRequest;
import com.goawaygo.backend.auth.dto.SignupRequest;
import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.common.i18n.Lang;
import com.goawaygo.backend.ratelimit.RateLimitProperties;
import com.goawaygo.backend.ratelimit.RateLimiter;
import com.goawaygo.backend.user.User;
import com.goawaygo.backend.user.UserRepository;
import com.goawaygo.backend.user.UserResponse;

@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private static final int BCRYPT_MAX_BYTES = 72;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenService accessTokens;

	private final RefreshTokenService refreshTokens;

	private final GoogleIdTokenVerifier googleVerifier;

	private final RateLimiter rateLimiter;

	private final RateLimitProperties rateLimits;

	private final AccountEmailService accountEmails;

	private final TeamOnlyAccess teamOnly;

	/** Compared against when the email is unknown, so "no such user" takes as long as "wrong password". */
	private final String dummyPasswordHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
			RefreshTokenService refreshTokens, GoogleIdTokenVerifier googleVerifier, RateLimiter rateLimiter,
			RateLimitProperties rateLimits, AccountEmailService accountEmails, TeamOnlyAccess teamOnly) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
		this.googleVerifier = googleVerifier;
		this.rateLimiter = rateLimiter;
		this.rateLimits = rateLimits;
		this.accountEmails = accountEmails;
		this.teamOnly = teamOnly;
		this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
	}

	@Transactional
	public UserResponse signup(SignupRequest request) {
		if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "password_too_long", "Password is too long.");
		}
		String email = User.normalizeEmail(request.email());
		// dev and test: only the team signs up (no email is sent to anyone else).
		this.teamOnly.check(email);
		if (this.users.existsByEmail(email)) {
			throw emailTaken();
		}
		try {
			User created = User.withPassword(email, request.name(), this.passwordEncoder.encode(request.password()));
			// Emails and pushes speak the language the account was made in, until the apps say otherwise.
			created.setLanguage(Lang.current().code());
			User user = this.users.saveAndFlush(created);
			// Sent after the transaction commits, in the background.
			this.accountEmails.sendVerificationEmail(user);
			return UserResponse.from(user);
		}
		catch (DataIntegrityViolationException ex) {
			// Two sign-ups with the same email at the same moment: the unique constraint catches the second.
			throw emailTaken();
		}
	}

	@Transactional
	public Session signin(SigninRequest request, String userAgent) {
		String email = User.normalizeEmail(request.email());
		// Per account, on top of the per-IP limit: stops password guessing spread across many IPs. Checked before
		// the password, so every attempt counts, right or wrong.
		this.rateLimiter.consume("signin-email", email, this.rateLimits.signinPerEmail());
		User user = this.users.findByEmail(email).orElse(null);
		String hash = (user != null && user.getPasswordHash() != null) ? user.getPasswordHash() : this.dummyPasswordHash;
		boolean matches = this.passwordEncoder.matches(request.password(), hash);
		if (user == null || user.getPasswordHash() == null || !matches) {
			// Same response for unknown email and wrong password, so attackers can't probe which emails exist.
			throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Incorrect email or password.");
		}
		// After the password check, so the answer doesn't reveal which emails have an account.
		this.teamOnly.check(user.getEmail());
		return startSession(user, this.refreshTokens.issue(user.getId(), userAgent), user.cancelDeletion());
	}

	/**
	 * Signs in with a Google ID token. The token is verified first; only Google's verified claims are used.
	 * <ol>
	 * <li>Known Google account: sign in.</li>
	 * <li>Unknown Google account, email already registered: link the Google account. If that account's email was
	 * never verified, its password is removed and its sessions revoked, because whoever registered it may not own
	 * the email (pre-account takeover).</li>
	 * <li>New email: create a passwordless, verified account.</li>
	 * </ol>
	 */
	@Transactional
	public Session googleSignin(String idToken, String userAgent) {
		GoogleIdTokenVerifier.GoogleIdentity google = this.googleVerifier.verify(idToken);
		if (google.email() == null || !google.emailVerified()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "google_email_not_verified",
					"Your Google account's email is not verified. Verify it with Google, or sign up with email.");
		}
		// dev and test: only the team, before any account is created or linked.
		this.teamOnly.check(google.email());

		User user = this.users.findByGoogleSubject(google.subject()).orElse(null);
		if (user == null) {
			user = this.users.findByEmail(User.normalizeEmail(google.email()))
				.map(existing -> linkGoogle(existing, google))
				.orElseGet(() -> createGoogleUser(google));
		}
		user.setProfilePictureUrlIfMissing(google.pictureUrl());
		return startSession(user, this.refreshTokens.issue(user.getId(), userAgent), user.cancelDeletion());
	}

	private User linkGoogle(User existing, GoogleIdTokenVerifier.GoogleIdentity google) {
		if (existing.getGoogleSubject() != null) {
			// The email belongs to a different Google account than the one signing in. Don't silently switch.
			throw new ApiException(HttpStatus.CONFLICT, "email_linked_to_other_google_account",
					"This email is already linked to a different Google account.");
		}
		if (!existing.isEmailVerified() && existing.getPasswordHash() != null) {
			existing.removePassword();
			int revoked = this.refreshTokens.revokeAllForUser(existing.getId());
			log.info("Google sign-in took over unverified account {}: password removed, {} session(s) revoked",
					existing.getId(), revoked);
		}
		existing.linkGoogle(google.subject());
		return existing;
	}

	private User createGoogleUser(GoogleIdTokenVerifier.GoogleIdentity google) {
		String email = User.normalizeEmail(google.email());
		User created = User.withGoogle(email, displayName(google.name(), email), google.subject(), null);
		created.setLanguage(Lang.current().code());
		return this.users.saveAndFlush(created);
	}

	/** Google's name if present (max 100 characters), otherwise the part of the email before the @. */
	private static String displayName(String googleName, String email) {
		String name = (googleName == null || googleName.isBlank()) ? email.substring(0, email.indexOf('@')) : googleName.trim();
		return name.length() <= 100 ? name : name.substring(0, 100);
	}

	@Transactional(noRollbackFor = ApiException.class)
	public Session refresh(String rawRefreshToken, String userAgent) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
					"Your session has expired. Please sign in again.");
		}
		RefreshTokenService.Rotation rotation = this.refreshTokens.rotate(rawRefreshToken, userAgent);
		User user = this.users.findById(rotation.userId())
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
					"Your session has expired. Please sign in again."));
		// dev and test: sessions from before the restriction end too.
		this.teamOnly.check(user.getEmail());
		return startSession(user, rotation.refreshToken(), false);
	}

	@Transactional
	public void logout(String rawRefreshToken) {
		if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
			this.refreshTokens.revoke(rawRefreshToken);
		}
	}

	/** @param restored the sign-in cancelled the account's pending deletion */
	Session startSession(User user, IssuedRefreshToken refreshToken, boolean restored) {
		if (restored) {
			log.info("User {} signed in again: account deletion cancelled", user.getId());
		}
		if (this.teamOnly.isTeam(user.getEmail()) && !user.isAdmin()) {
			// dev and test: the team's account is the environment's admin.
			user.makeAdmin();
			log.info("User {} is a team account here: made admin", user.getId());
		}
		IssuedToken accessToken = this.accessTokens.issue(user.getId());
		long expiresIn = Duration.between(accessToken.issuedAt(), accessToken.expiresAt()).toSeconds();
		return new Session(UserResponse.from(user), accessToken.value(), expiresIn, refreshToken, restored);
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, "email_already_registered",
				"An account with this email already exists.");
	}

	public record Session(UserResponse user, String accessToken, long expiresIn, IssuedRefreshToken refreshToken,
			boolean restored) {
	}

}
