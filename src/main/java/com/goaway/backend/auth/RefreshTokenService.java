package com.goaway.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goaway.backend.common.error.ApiException;

/**
 * Refresh tokens: random 256-bit values, stored only as SHA-256 hashes, rotated on every use.
 *
 * <p>Reuse detection: if a token that was already rotated is presented again, someone has a copy of an old
 * token (it may have been stolen). The whole token family is revoked, signing out every device that descends
 * from that sign-in.
 */
@Service
public class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private static final int TOKEN_BYTES = 32;

	private static final int MAX_USER_AGENT_LENGTH = 255;

	private final SecureRandom random = new SecureRandom();

	private final RefreshTokenRepository tokens;

	private final AuthProperties properties;

	private final Clock clock;

	public RefreshTokenService(RefreshTokenRepository tokens, AuthProperties properties, Clock clock) {
		this.tokens = tokens;
		this.properties = properties;
		this.clock = clock;
	}

	/** A new token starting a new family (a fresh sign-in). */
	@Transactional
	public IssuedRefreshToken issue(long userId, String userAgent) {
		return create(userId, UUID.randomUUID(), userAgent).issued();
	}

	/**
	 * Exchanges a valid refresh token for a new one (same family) and revokes the old one.
	 * @throws ApiException 401 if the token is unknown, expired, revoked or reused
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public Rotation rotate(String rawToken, String userAgent) {
		Instant now = this.clock.instant();
		RefreshToken current = this.tokens.findByTokenHash(hash(rawToken)).orElseThrow(RefreshTokenService::invalid);

		if (current.isRevoked()) {
			if (current.wasRotated()) {
				// Kept by noRollbackFor: the revocation must be committed even though we reject the request.
				int revoked = this.tokens.revokeFamily(current.getFamilyId(), now);
				log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in family {}",
						current.getUserId(), revoked, current.getFamilyId());
			}
			throw invalid();
		}
		if (current.isExpired(now)) {
			throw invalid();
		}

		Created next = create(current.getUserId(), current.getFamilyId(), userAgent);
		current.rotateTo(next.entity(), now);
		return new Rotation(current.getUserId(), next.issued());
	}

	/** Revokes a token (logout). Unknown or already-revoked tokens are ignored, so logout is idempotent. */
	@Transactional
	public void revoke(String rawToken) {
		this.tokens.findByTokenHash(hash(rawToken)).ifPresent(token -> token.revoke(this.clock.instant()));
	}

	/** Signs the user out everywhere: revokes all of their refresh tokens. */
	@Transactional
	public int revokeAllForUser(long userId) {
		return this.tokens.revokeAllForUser(userId, this.clock.instant());
	}

	private Created create(long userId, UUID familyId, String userAgent) {
		byte[] bytes = new byte[TOKEN_BYTES];
		this.random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(this.properties.refreshTokenTtl());
		RefreshToken entity = this.tokens
			.save(new RefreshToken(userId, hash(raw), familyId, now, expiresAt, truncate(userAgent)));
		return new Created(entity, new IssuedRefreshToken(raw, expiresAt));
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available in the JDK", ex);
		}
	}

	private static String truncate(String userAgent) {
		if (userAgent == null) {
			return null;
		}
		return userAgent.length() <= MAX_USER_AGENT_LENGTH ? userAgent : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
				"Your session has expired. Please sign in again.");
	}

	public record IssuedRefreshToken(String value, Instant expiresAt) {
	}

	public record Rotation(long userId, IssuedRefreshToken refreshToken) {
	}

	private record Created(RefreshToken entity, IssuedRefreshToken issued) {
	}

}
