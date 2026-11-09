package com.goawaygo.backend.notifications;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;

import com.goawaygo.backend.external.ExternalApis;
import com.goawaygo.backend.notifications.Notifications.Setting;

/**
 * Sends push messages to a user's phones through Firebase Cloud Messaging (HTTP v1), <em>after</em> the change that
 * caused them is saved, and still inside the request: Cloud Run only gives the backend CPU while it handles one (the
 * same reason as {@code EmailOutbox}). A push that fails is logged and dropped; the bell still lists the notification.
 * Tokens Firebase no longer knows (app uninstalled) are deleted.
 */
@Component
class PushSender {

	private static final Logger log = LoggerFactory.getLogger(PushSender.class);

	private final ApplicationEventPublisher events;

	private final Notifications notifications;

	private final ExternalApis apis;

	private final GoogleAccessToken accessToken;

	private final NotificationProperties properties;

	PushSender(ApplicationEventPublisher events, Notifications notifications, ExternalApis apis,
			GoogleAccessToken accessToken, NotificationProperties properties) {
		this.events = events;
		this.notifications = notifications;
		this.apis = apis;
		this.accessToken = accessToken;
		this.properties = properties;
	}

	/**
	 * A push for one user.
	 *
	 * @param setting the switch that turns this kind of push off
	 * @param data what the app opens when it is tapped (type, post, profile or plan id)
	 */
	record Push(long userId, Setting setting, String title, String body, Map<String, String> data) {
	}

	/** Sends {@code push} once the current transaction commits (immediately if there is none). */
	void queue(Push push) {
		if (this.properties.pushEnabled()) {
			this.events.publishEvent(push);
		}
	}

	@TransactionalEventListener(fallbackExecution = true)
	void onCommitted(Push push) {
		if (!this.notifications.settings(push.userId()).allows(push.setting())) {
			return;
		}
		for (String token : this.notifications.devices(push.userId())) {
			send(token, push);
		}
	}

	private void send(String token, Push push) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("token", token);
		message.put("notification", Map.of("title", push.title(), "body", push.body()));
		message.put("data", push.data());
		message.put("android", Map.of("notification", Map.of("channel_id", "goawaygo")));
		try {
			String bearer = this.accessToken.get();
			this.apis.client("fcm")
				.call((http, key) -> http.post()
					.uri("/v1/projects/{project}/messages:send", this.properties.fcmProject())
					.header("Authorization", "Bearer " + bearer)
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("message", message))
					.retrieve()
					.toBodilessEntity());
		}
		catch (HttpClientErrorException ex) {
			// 404 UNREGISTERED: the app was uninstalled or its token replaced; 400: a token that was never valid.
			if (ex.getStatusCode() == HttpStatus.NOT_FOUND || ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
				this.notifications.forgetDevice(token);
			}
			else {
				log.warn("Push not sent: {}", ex.getStatusCode());
			}
		}
		catch (RuntimeException ex) {
			log.warn("Push not sent: {}", ex.toString());
		}
	}

}
