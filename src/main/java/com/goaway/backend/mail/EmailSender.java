package com.goaway.backend.mail;

/**
 * Delivers an email. The only implementation talks SMTP ({@link SmtpEmailSender}), which works with Mailpit locally
 * and Resend (or any other provider) in deployed environments, so switching providers is configuration only.
 */
public interface EmailSender {

	void send(EmailMessage message);

}
