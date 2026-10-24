package com.goawaygo.backend.mail;

import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends over SMTP (Mailpit locally, Resend when deployed) as a multipart text + HTML message. */
@Component
public class SmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;

	private final MailProperties properties;

	public SmtpEmailSender(JavaMailSender mailSender, MailProperties properties) {
		this.mailSender = mailSender;
		this.properties = properties;
	}

	@Override
	public void send(EmailMessage message) {
		try {
			MimeMessage mime = this.mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
			helper.setFrom(this.properties.from());
			helper.setTo(message.to());
			helper.setSubject(message.subject());
			helper.setText(message.text(), message.html());
			this.mailSender.send(mime);
		}
		catch (MessagingException ex) {
			throw new MailSendException("Could not build email", ex);
		}
	}

}
