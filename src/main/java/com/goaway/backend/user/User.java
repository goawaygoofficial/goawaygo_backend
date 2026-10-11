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

	/** A new account created by Google Sign-In. Google has verified the email, so it starts verified. */
	public static User withGoogle(String email, String name, String googleSubject, String profilePictureUrl) {
		User user = new User();
		user.email = normalizeEmail(email);
		user.name = name.trim();
		user.googleSubject = googleSubject;
		user.emailVerified = true;
		user.profilePictureUrl = profilePictureUrl;
		return user;
	}

	/** Links a Google account; Google verified the email, so the email is now verified too. */
	public void linkGoogle(String googleSubject) {
		this.googleSubject = googleSubject;
		this.emailVerified = true;
	}

	/**
	 * Removes the password. Used when a Google account takes over an email whose password account was never
	 * verified: whoever set that password may not own the email ("pre-account takeover").
	 */
	public void removePassword() {
		this.passwordHash = null;
	}

	/** Sets a new password (already hashed). */
	public void changePassword(String newPasswordHash) {
		this.passwordHash = newPasswordHash;
	}

	/** The user proved they own the email (verification link, password-reset link or Google). */
	public void markEmailVerified() {
		this.emailVerified = true;
	}

	public void setProfilePictureUrlIfMissing(String url) {
		if (this.profilePictureUrl == null && url != null && url.length() <= 2048) {
			this.profilePictureUrl = url;
		}
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
