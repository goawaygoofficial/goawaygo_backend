package com.goawaygo.backend.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goawaygo.backend.common.error.ApiException;

/**
 * Issues and redeems the single-use tokens behind emailed links. Tokens are 256-bit random values; only their
 * SHA-256 hash is stored. Issuing a new token retires the user's older unused ones of the same purpose.
 */
@Service
public class UserActionTokenService {

	private static final int TOKEN_BYTES = 32;

	private final SecureRandom random = new SecureRandom();

	private final UserActionTokenRepository tokens;

	private final Clock clock;

	public UserActionTokenService(UserActionTokenRepository tokens, Clock clock) {
		this.tokens = tokens;
		this.clock = clock;
	}

	/** @return the raw token to put in the emailed link (never stored) */
	@Transactional
	public String issue(long userId, UserActionToken.Purpose purpose, Duration ttl) {
		Instant now = this.clock.instant();
		this.tokens.retireUnused(userId, purpose, now);
		byte[] bytes = new byte[TOKEN_BYTES];
		this.random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		this.tokens.save(new UserActionToken(userId, purpose, RefreshTokenService.hash(raw), now, now.plus(ttl)));
		return raw;
	}

	/**
	 * Marks the token used and returns its user.
	 * @throws ApiException 400 {@code invalid_or_expired_link} if unknown, used, expired or of another purpose
	 */
	// An invalid link is an expected outcome: it must not mark the caller's transaction rollback-only.
	@Transactional(noRollbackFor = ApiException.class)
	public long redeem(String rawToken, UserActionToken.Purpose purpose) {
		Instant now = this.clock.instant();
		UserActionToken token = (rawToken == null || rawToken.isBlank()) ? null
				: this.tokens.findByTokenHash(RefreshTokenService.hash(rawToken)).orElse(null);
		if (token == null || !token.isUsable(purpose, now)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_or_expired_link",
					"This link is invalid or has expired. Please request a new one.");
		}
		token.markUsed(now);
		return token.getUserId();
	}

}
