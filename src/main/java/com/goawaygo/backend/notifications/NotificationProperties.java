package com.goawaygo.backend.notifications;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code goawaygo.notifications.*}: the bell and push messages (4.5). Firebase Cloud Messaging and the Google token
 * it needs are configured under {@code goawaygo.external.providers.fcm} and {@code gcpmetadata}.
 *
 * @param fcmProject Firebase project that sends pushes; empty sends none (local runs, tests that don't need them)
 * @param keep how long notifications are kept
 * @param likePushEvery at most one "liked your post" push per post this often
 * @param reminderZone the day trip reminders count in (India first; per user with 4.7)
 */
@Validated
@ConfigurationProperties("goawaygo.notifications")
public record NotificationProperties(@DefaultValue("") String fcmProject, @DefaultValue("90d") Duration keep,
		@DefaultValue("1h") Duration likePushEvery, @NotNull @DefaultValue("Asia/Kolkata") ZoneId reminderZone) {

	boolean pushEnabled() {
		return !this.fcmProject.isBlank();
	}

}
