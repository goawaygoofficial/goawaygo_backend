package com.goawaygo.backend.mail;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Records emails instead of sending them, so tests can read them and follow their links. */
public class RecordingEmailSender implements EmailSender {

	private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_%-]+)");

	private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

	@Override
	public void send(EmailMessage message) {
		this.sent.add(message);
	}

	public List<EmailMessage> sentTo(String email) {
		return this.sent.stream().filter(m -> m.to().equals(email)).toList();
	}

	public Optional<EmailMessage> latestTo(String email) {
		List<EmailMessage> messages = sentTo(email);
		return messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(messages.size() - 1));
	}

	/** The {@code token} query parameter of the link in the email's plain-text body. */
	public static String tokenFrom(EmailMessage message) {
		Matcher matcher = TOKEN.matcher(message.text());
		if (!matcher.find()) {
			throw new AssertionError("No link with a token in email: " + message.subject());
		}
		return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
	}

	/** Registers the recorder as the {@link EmailSender} used by the application in tests. */
	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		RecordingEmailSender recordingEmailSender() {
			return new RecordingEmailSender();
		}

	}

}
