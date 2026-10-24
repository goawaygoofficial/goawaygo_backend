package com.goawaygo.backend.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goawaygo.backend.auth.UserActionToken.Purpose;
import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.config.WebProperties;
import com.goawaygo.backend.mail.EmailOutbox;
import com.goawaygo.backend.mail.EmailTemplates;
import com.goawaygo.backend.ratelimit.RateLimitProperties;
import com.goawaygo.backend.ratelimit.RateLimiter;
import com.goawaygo.backend.user.User;
import com.goawaygo.backend.user.UserRepository;

/**
 * Password reset and email verification by emailed link.
 *
 * <p>Forgot-password behaves identically whether or not the account exists (same response, email sent in the
 * background), so it can't be used to find out who is registered.
 */
@Service
public class AccountEmailService {

	private static final Logger log = LoggerFactory.getLogger(AccountEmailService.class);

	private static final int BCRYPT_MAX_BYTES = 72;

	private final UserRepository users;

	private final UserActionTokenService actionTokens;

	private final RefreshTokenService refreshTokens;

	private final PasswordEncoder passwordEncoder;

	private final EmailOutbox outbox;

	private final AuthProperties authProperties;

	private final WebProperties web;

	private final RateLimiter rateLimiter;

	private final RateLimitProperties rateLimits;

	public AccountEmailService(UserRepository users, UserActionTokenService actionTokens,
			RefreshTokenService refreshTokens, PasswordEncoder passwordEncoder, EmailOutbox outbox,
			AuthProperties authProperties, WebProperties web, RateLimiter rateLimiter,
			RateLimitProperties rateLimits) {
		this.users = users;
		this.actionTokens = actionTokens;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.outbox = outbox;
		this.authProperties = authProperties;
		this.web = web;
		this.rateLimiter = rateLimiter;
		this.rateLimits = rateLimits;
	}

	/** Emails a reset link if the account exists. Same outcome either way for the caller. */
	@Transactional
	public void requestPasswordReset(String rawEmail) {
		String email = User.normalizeEmail(rawEmail);
		// Per email, regardless of whether it exists: stops flooding someone's inbox with reset emails.
		this.rateLimiter.consume("forgot-password-email", email, this.rateLimits.forgotPasswordPerEmail());
		this.users.findByEmail(email).ifPresent(user -> {
			String token = this.actionTokens.issue(user.getId(), Purpose.PASSWORD_RESET,
					this.authProperties.passwordResetTtl());
			this.outbox.queue(EmailTemplates.passwordReset(user.getEmail(), user.getName(),
					link("/reset-password", token), this.authProperties.passwordResetTtl().toMinutes()));
		});
	}

	/**
	 * Sets a new password from a reset link. The link proves the user owns the email, so the email becomes verified.
	 * Every existing session is signed out, in case the reset was triggered because someone else had access.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public void resetPassword(String token, String newPassword) {
		if (newPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "password_too_long", "Password is too long.");
		}
		long userId = this.actionTokens.redeem(token, Purpose.PASSWORD_RESET);
		User user = this.users.findById(userId).orElseThrow(AccountEmailService::invalidLink);
		user.changePassword(this.passwordEncoder.encode(newPassword));
		user.markEmailVerified();
		int revoked = this.refreshTokens.revokeAllForUser(userId);
		log.info("Password reset for user {}; {} session(s) signed out", userId, revoked);
	}

	/** Sends the "verify your email" link (after sign-up, or on request). No-op if already verified. */
	@Transactional
	public void sendVerificationEmail(User user) {
		if (user.isEmailVerified()) {
			return;
		}
		String token = this.actionTokens.issue(user.getId(), Purpose.EMAIL_VERIFICATION,
				this.authProperties.emailVerificationTtl());
		this.outbox.queue(EmailTemplates.verifyEmail(user.getEmail(), user.getName(), link("/verify-email", token),
				this.authProperties.emailVerificationTtl().toHours()));
	}

	/** Resends the verification link to the signed-in user (rate limited per user). */
	@Transactional
	public void resendVerificationEmail(long userId) {
		this.rateLimiter.consume("verification-email-user", Long.toString(userId),
				this.rateLimits.verificationEmailPerUser());
		User user = this.users.findById(userId)
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "You need to sign in to do this."));
		if (user.isEmailVerified()) {
			throw new ApiException(HttpStatus.CONFLICT, "email_already_verified", "Your email is already verified.");
		}
		sendVerificationEmail(user);
	}

	@Transactional(noRollbackFor = ApiException.class)
	public void verifyEmail(String token) {
		long userId = this.actionTokens.redeem(token, Purpose.EMAIL_VERIFICATION);
		this.users.findById(userId).orElseThrow(AccountEmailService::invalidLink).markEmailVerified();
	}

	private String link(String path, String token) {
		return this.web.url(path) + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
	}

	private static ApiException invalidLink() {
		return new ApiException(HttpStatus.BAD_REQUEST, "invalid_or_expired_link",
				"This link is invalid or has expired. Please request a new one.");
	}

}
