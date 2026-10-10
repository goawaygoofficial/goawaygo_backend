package com.goaway.backend.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A Goaway account. Table: {@code users} (see V2 migration). */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Always lower-cased and trimmed (see {@link #normalizeEmail}). */
	@Column(nullable = false, length = 320)
	private String email;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "password_hash", length = 100)
	private String passwordHash;

	@Column(name = "google_subject")
	private String googleSubject;

	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	@Column(name = "profile_picture_url", length = 2048)
	private String profilePictureUrl;

	@Column(length = 32)
	private String phone;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected User() {
	}

	/** A new account that signs in with email and password. */
	public static User withPassword(String email, String name, String passwordHash) {
		User user = new User();
		user.email = normalizeEmail(email);
		user.name = name.trim();
		user.passwordHash = passwordHash;
		return user;
	}

	public static String normalizeEmail(String email) {
		return email.trim().toLowerCase(java.util.Locale.ROOT);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return this.id;
	}

	public String getEmail() {
		return this.email;
	}

	public String getName() {
		return this.name;
	}

	public String getPasswordHash() {
		return this.passwordHash;
	}

	public String getGoogleSubject() {
		return this.googleSubject;
	}

	public boolean isEmailVerified() {
		return this.emailVerified;
	}

	public String getProfilePictureUrl() {
		return this.profilePictureUrl;
	}

	public String getPhone() {
		return this.phone;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
