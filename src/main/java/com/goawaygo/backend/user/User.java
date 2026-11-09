package com.goawaygo.backend.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A GoAwayGo account. Table: {@code users} (see V2 migration). */
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

	/** ISO 3166 code (e.g. {@code IN}); null until the user picks one. */
	@Column(length = 2)
	private String country;

	/** ISO 4217 code (e.g. {@code INR}); null until the user picks one. */
	@Column(length = 3)
	private String currency;

	/** A new email waiting for its confirmation link. */
	@Column(name = "pending_email", length = 320)
	private String pendingEmail;

	/** When the user asked to delete the account (deleted for good 30 days later); null otherwise. */
	@Column(name = "deletion_requested_at")
	private Instant deletionRequestedAt;

	/** Reviews Wanderbook reports (set by hand in the database). */
	@Column(name = "is_admin", nullable = false)
	private boolean admin;

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

	/** Name (already validated: 1-100 characters), country and currency (validated codes, or null). */
	public void updateDetails(String name, String country, String currency) {
		this.name = name.trim();
		this.country = country;
		this.currency = currency;
	}

	/** Uses {@code profilePictureUrl} (a Wanderbook photo) as the account photo, or none. */
	public void setProfilePictureUrl(String url) {
		this.profilePictureUrl = url;
	}

	public void requestEmailChange(String newEmail) {
		this.pendingEmail = normalizeEmail(newEmail);
	}

	/** The confirmation link proved the user owns the pending email: it becomes the account's (verified) email. */
	public void confirmEmailChange() {
		this.email = this.pendingEmail;
		this.pendingEmail = null;
		this.emailVerified = true;
	}

	public void requestDeletion(Instant now) {
		this.deletionRequestedAt = now;
	}

	/** Signing in again cancels a pending deletion. True if one was cancelled. */
	public boolean cancelDeletion() {
		boolean pending = this.deletionRequestedAt != null;
		this.deletionRequestedAt = null;
		return pending;
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

	public String getCountry() {
		return this.country;
	}

	public String getCurrency() {
		return this.currency;
	}

	public String getPendingEmail() {
		return this.pendingEmail;
	}

	public Instant getDeletionRequestedAt() {
		return this.deletionRequestedAt;
	}

	public boolean isAdmin() {
		return this.admin;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
