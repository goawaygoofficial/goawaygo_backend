package com.goaway.backend.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single-use token sent by email (only its SHA-256 hash). Table: {@code user_action_tokens} (V3 migration). */
@Entity
@Table(name = "user_action_tokens")
public class UserActionToken {

	public enum Purpose {

		PASSWORD_RESET, EMAIL_VERIFICATION

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private Purpose purpose;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected UserActionToken() {
	}

	UserActionToken(long userId, Purpose purpose, String tokenHash, Instant createdAt, Instant expiresAt) {
		this.userId = userId;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	boolean isUsable(Purpose expected, Instant now) {
		return this.purpose == expected && this.usedAt == null && now.isBefore(this.expiresAt);
	}

	void markUsed(Instant now) {
		this.usedAt = now;
	}

	long getUserId() {
		return this.userId;
	}

}
