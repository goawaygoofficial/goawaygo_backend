package com.goaway.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goaway.backend.auth.AccessTokenService.IssuedToken;
import com.goaway.backend.auth.RefreshTokenService.IssuedRefreshToken;
import com.goaway.backend.auth.dto.SigninRequest;
import com.goaway.backend.auth.dto.SignupRequest;
import com.goaway.backend.common.error.ApiException;
import com.goaway.backend.ratelimit.RateLimitProperties;
import com.goaway.backend.ratelimit.RateLimiter;
import com.goaway.backend.user.User;
import com.goaway.backend.user.UserRepository;
import com.goaway.backend.user.UserResponse;

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

	/** Compared against when the email is unknown, so "no such user" takes as long as "wrong password". */
	private final String dummyPasswordHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
			RefreshTokenService refreshTokens, GoogleIdTokenVerifier googleVerifier, RateLimiter rateLimiter,
			RateLimitProperties rateLimits) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
		this.googleVerifier = googleVerifier;
		this.rateLimiter = rateLimiter;
		this.rateLimits = rateLimits;
		this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
	}

	@Transactional
	public UserResponse signup(SignupRequest request) {
		if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "password_too_long", "Password is too long.");
		}
		String email = User.normalizeEmail(request.email());
		if (this.users.existsByEmail(email)) {
			throw emailTaken();
		}
		try {
			User user = this.users.saveAndFlush(
					User.withPassword(email, request.name(), this.passwordEncoder.encode(request.password())));
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
		return startSession(user, this.refreshTokens.issue(user.getId(), userAgent));
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

		User user = this.users.findByGoogleSubject(google.subject()).orElse(null);
		if (user == null) {
			user = this.users.findByEmail(User.normalizeEmail(google.email()))
				.map(existing -> linkGoogle(existing, google))
				.orElseGet(() -> createGoogleUser(google));
		}
		user.setProfilePictureUrlIfMissing(google.pictureUrl());
		return startSession(user, this.refreshTokens.issue(user.getId(), userAgent));
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
		return this.users.saveAndFlush(User.withGoogle(email, displayName(google.name(), email), google.subject(), null));
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
		return startSession(user, rotation.refreshToken());
	}

	@Transactional
	public void logout(String rawRefreshToken) {
		if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
			this.refreshTokens.revoke(rawRefreshToken);
		}
	}

	private Session startSession(User user, IssuedRefreshToken refreshToken) {
		IssuedToken accessToken = this.accessTokens.issue(user.getId());
		long expiresIn = Duration.between(accessToken.issuedAt(), accessToken.expiresAt()).toSeconds();
		return new Session(UserResponse.from(user), accessToken.value(), expiresIn, refreshToken);
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, "email_already_registered",
				"An account with this email already exists.");
	}

	public record Session(UserResponse user, String accessToken, long expiresIn, IssuedRefreshToken refreshToken) {
	}

}
