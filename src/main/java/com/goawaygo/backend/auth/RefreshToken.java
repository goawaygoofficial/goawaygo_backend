package com.goawaygo.backend.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A stored refresh token (only its SHA-256 hash). Table: {@code refresh_tokens} (see V2 migration). */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "replaced_by")
	private Long replacedBy;

	@Column(name = "user_agent")
	private String userAgent;

	protected RefreshToken() {
	}

	RefreshToken(long userId, String tokenHash, UUID familyId, Instant createdAt, Instant expiresAt, String userAgent) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.familyId = familyId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.userAgent = userAgent;
	}

	boolean isRevoked() {
		return this.revokedAt != null;
	}

	boolean isExpired(Instant now) {
		return !now.isBefore(this.expiresAt);
	}

	/** True if this token was rotated (replaced by a newer one), as opposed to revoked by logout. */
	boolean wasRotated() {
		return this.replacedBy != null;
	}

	void revoke(Instant now) {
		if (this.revokedAt == null) {
			this.revokedAt = now;
		}
	}

	void rotateTo(RefreshToken successor, Instant now) {
		revoke(now);
		this.replacedBy = successor.id;
	}

	Long getId() {
		return this.id;
	}

	long getUserId() {
		return this.userId;
	}

	UUID getFamilyId() {
		return this.familyId;
	}

}
