package com.goawaygo.backend.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goawaygo.backend.auth.UserActionToken.Purpose;
import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.common.i18n.Lang;
import com.goawaygo.backend.config.WebProperties;
import com.goawaygo.backend.mail.EmailOutbox;
import com.goawaygo.backend.mail.EmailTemplates;
import com.goawaygo.backend.media.R2Storage;
import com.goawaygo.backend.ratelimit.RateLimitProperties;
import com.goawaygo.backend.ratelimit.RateLimiter;
import com.goawaygo.backend.user.User;
import com.goawaygo.backend.user.UserRepository;
import com.goawaygo.backend.user.UserResponse;

/**
 * Changing the password or email, and deleting the account (4.6). Each of them first asks the user to confirm it's
 * them: their password, or Google for accounts without one.
 *
 * <p>Deleting gives 30 days to change your mind: the account is signed out everywhere and hidden from others at once
 * (its pushes stop too), signing in again keeps it ({@link AuthService}), and after 30 days {@link #purgeDue} deletes
 * its photo files and then the account, which takes everything it owns with it (every table cascades).
 */
@Service
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	public static final Duration GRACE = Duration.ofDays(30);

	/** Accounts deleted per daily run; any more wait for the next run. */
	private static final int PURGE_BATCH = 50;

	private static final int BCRYPT_MAX_BYTES = 72;

	private static final String HELP_EMAIL = "support@goawaygo.com";

	/** "9 December 2026", in the user's language. */
	private static String date(Instant instant, Lang lang) {
		return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(lang.locale()).withZone(ZoneOffset.UTC)
			.format(instant);
	}

	/** Password guesses on the "confirm it's you" step, per account. */
	private static final RateLimitProperties.Limit CONFIRM_PER_USER = new RateLimitProperties.Limit(5,
			Duration.ofMinutes(15));

	/** Confirmation emails to new addresses, per account. */
	private static final RateLimitProperties.Limit EMAIL_CHANGES_PER_USER = new RateLimitProperties.Limit(5,
			Duration.ofDays(1));

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final GoogleIdTokenVerifier googleVerifier;

	private final RefreshTokenService refreshTokens;

	private final UserActionTokenService actionTokens;

	private final AuthService authService;

	private final EmailOutbox outbox;

	private final AuthProperties authProperties;

	private final WebProperties web;

	private final RateLimiter rateLimiter;

	private final R2Storage storage;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public AccountService(UserRepository users, PasswordEncoder passwordEncoder, GoogleIdTokenVerifier googleVerifier,
			RefreshTokenService refreshTokens, UserActionTokenService actionTokens, AuthService authService,
			EmailOutbox outbox, AuthProperties authProperties, WebProperties web, RateLimiter rateLimiter,
			R2Storage storage, JdbcTemplate jdbc, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.googleVerifier = googleVerifier;
		this.refreshTokens = refreshTokens;
		this.actionTokens = actionTokens;
		this.authService = authService;
		this.outbox = outbox;
		this.authProperties = authProperties;
		this.web = web;
		this.rateLimiter = rateLimiter;
		this.storage = storage;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * "Confirm it's you": the current password, or a fresh Google ID token of the account's Google login.
	 * @param googleIdToken from Google Sign-In (the app), or exchanged from the website's authorization code
	 */
	public record Proof(String password, String googleIdToken) {
	}

	/**
	 * Changes the password (or sets one on a Google account), signs out every device and returns a new session for
	 * this one.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public AuthService.Session changePassword(long userId, Proof proof, String newPassword, String userAgent) {
		if (newPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "password_too_long", "Password is too long.");
		}
		User user = active(userId);
		confirm(user, proof);
		user.changePassword(this.passwordEncoder.encode(newPassword));
		int revoked = this.refreshTokens.revokeAllForUser(userId);
		log.info("Password changed for user {}; {} session(s) signed out", userId, revoked);
		this.outbox.queue(EmailTemplates.passwordChanged(user.getEmail(), user.getName(), this.web.url("/profile"),
				Lang.orEnglish(user.getLanguage())));
		return this.authService.startSession(user, this.refreshTokens.issue(userId, userAgent), false);
	}

	/** Emails a confirmation link to the new address; the account keeps its email until the link is opened. */
	@Transactional(noRollbackFor = ApiException.class)
	public UserResponse requestEmailChange(long userId, Proof proof, String newEmail) {
		User user = active(userId);
		confirm(user, proof);
		String email = User.normalizeEmail(newEmail);
		if (email.equals(user.getEmail())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "same_email", "That is already your email.");
		}
		if (this.users.existsByEmail(email)) {
			throw emailTaken();
		}
		this.rateLimiter.consume("email-change-user", Long.toString(userId), EMAIL_CHANGES_PER_USER);
		user.requestEmailChange(email);
		Duration ttl = this.authProperties.emailVerificationTtl();
		String token = this.actionTokens.issue(userId, Purpose.EMAIL_CHANGE, ttl);
		this.outbox.queue(EmailTemplates.confirmEmailChange(email, user.getName(), link("/confirm-email", token),
				ttl.toHours(), Lang.orEnglish(user.getLanguage())));
		return UserResponse.from(user);
	}

	/** The new address's link: it becomes the account's email, and the old address is told. */
	@Transactional(noRollbackFor = ApiException.class)
	public void confirmEmailChange(String token) {
		long userId = this.actionTokens.redeem(token, Purpose.EMAIL_CHANGE);
		User user = this.users.findById(userId).orElse(null);
		if (user == null || user.getPendingEmail() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_or_expired_link",
					"This link is invalid or has expired. Please request a new one.");
		}
		if (this.users.existsByEmail(user.getPendingEmail())) {
			throw emailTaken();
		}
		String oldEmail = user.getEmail();
		user.confirmEmailChange();
		try {
			this.users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw emailTaken();
		}
		log.info("User {} changed their email", userId);
		this.outbox.queue(EmailTemplates.emailChanged(oldEmail, user.getName(), user.getEmail(), HELP_EMAIL,
				Lang.orEnglish(user.getLanguage())));
	}

	/** Starts the 30 days: signed out everywhere, pushes stopped, hidden from others; an email says until when. */
	@Transactional(noRollbackFor = ApiException.class)
	public Instant requestDeletion(long userId, Proof proof) {
		User user = active(userId);
		confirm(user, proof);
		Instant now = this.clock.instant();
		user.requestDeletion(now);
		int revoked = this.refreshTokens.revokeAllForUser(userId);
		this.jdbc.update("DELETE FROM notification_devices WHERE user_id = ?", userId);
		Instant deleteOn = now.plus(GRACE);
		log.info("User {} asked to delete their account; {} session(s) signed out", userId, revoked);
		Lang lang = Lang.orEnglish(user.getLanguage());
		this.outbox.queue(EmailTemplates.deletionScheduled(user.getEmail(), user.getName(), date(deleteOn, lang),
				this.web.url("/profile"), lang));
		return deleteOn;
	}

	/**
	 * Deletes the accounts whose 30 days are over (the daily job): first their photo files in R2, then the account
	 * with everything it owns. An account whose files couldn't all be deleted waits for the next run, so no file is
	 * ever left behind.
	 * @return how many accounts were deleted
	 */
	public int purgeDue() {
		Instant cutoff = this.clock.instant().minus(GRACE);
		List<Long> due = this.jdbc.queryForList("""
				SELECT id FROM users WHERE deletion_requested_at < ? ORDER BY deletion_requested_at LIMIT ?""",
				Long.class, java.sql.Timestamp.from(cutoff), PURGE_BATCH);
		int deleted = 0;
		for (long userId : due) {
			List<String> files = this.jdbc.queryForList("SELECT object_key FROM wanderbook_photos WHERE owner_id = ?",
					String.class, userId);
			boolean filesGone = files.stream().map(this.storage::tryDelete).reduce(true, Boolean::logicalAnd);
			if (!filesGone) {
				log.warn("Account {} not deleted yet: some photo files are still in R2", userId);
				continue;
			}
			var account = this.jdbc.queryForList("SELECT email, name, language FROM users WHERE id = ?", userId);
			// Still waiting to be deleted (not signed in again meanwhile)?
			int rows = this.jdbc.update("DELETE FROM users WHERE id = ? AND deletion_requested_at < ?", userId,
					java.sql.Timestamp.from(cutoff));
			if (rows > 0) {
				deleted++;
				log.info("Account {} deleted ({} photo file(s))", userId, files.size());
				if (!account.isEmpty()) {
					this.outbox.queue(EmailTemplates.accountDeleted((String) account.get(0).get("email"),
							(String) account.get(0).get("name"), Lang.orEnglish((String) account.get(0).get("language"))));
				}
			}
		}
		return deleted;
	}

	private void confirm(User user, Proof proof) {
		String password = (proof == null) ? null : proof.password();
		String googleIdToken = (proof == null) ? null : proof.googleIdToken();
		if (password != null && !password.isEmpty() && user.getPasswordHash() != null) {
			this.rateLimiter.consume("confirm-identity-user", Long.toString(user.getId()), CONFIRM_PER_USER);
			if (!this.passwordEncoder.matches(password, user.getPasswordHash())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_password", "That password isn't right.");
			}
			return;
		}
		if (googleIdToken != null && !googleIdToken.isBlank() && user.getGoogleSubject() != null) {
			GoogleIdTokenVerifier.GoogleIdentity google = this.googleVerifier.verify(googleIdToken);
			if (!user.getGoogleSubject().equals(google.subject())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_google_account",
						"That is a different Google account. Use the one you sign in with.");
			}
			return;
		}
		throw new ApiException(HttpStatus.BAD_REQUEST, "confirm_identity",
				(user.getPasswordHash() != null) ? "Enter your current password." : "Confirm with Google first.");
	}

	private User active(long userId) {
		return this.users.findById(userId)
			.filter(user -> user.getDeletionRequestedAt() == null)
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "You need to sign in to do this."));
	}

	private String link(String path, String token) {
		return this.web.url(path) + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, "email_already_registered",
				"An account with this email already exists.");
	}

}
