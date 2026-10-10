package com.goaway.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
import com.goaway.backend.user.User;
import com.goaway.backend.user.UserRepository;
import com.goaway.backend.user.UserResponse;

@Service
public class AuthService {

	private static final int BCRYPT_MAX_BYTES = 72;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenService accessTokens;

	private final RefreshTokenService refreshTokens;

	/** Compared against when the email is unknown, so "no such user" takes as long as "wrong password". */
	private final String dummyPasswordHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
			RefreshTokenService refreshTokens) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
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
		User user = this.users.findByEmail(User.normalizeEmail(request.email())).orElse(null);
		String hash = (user != null && user.getPasswordHash() != null) ? user.getPasswordHash() : this.dummyPasswordHash;
		boolean matches = this.passwordEncoder.matches(request.password(), hash);
		if (user == null || user.getPasswordHash() == null || !matches) {
			// Same response for unknown email and wrong password, so attackers can't probe which emails exist.
			throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Incorrect email or password.");
		}
		return startSession(user, this.refreshTokens.issue(user.getId(), userAgent));
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
