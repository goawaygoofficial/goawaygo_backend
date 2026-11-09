package com.goawaygo.backend.notifications;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.ratelimit.RateLimitProperties;
import com.goawaygo.backend.ratelimit.RateLimiter;
import com.goawaygo.backend.user.User;
import com.goawaygo.backend.user.UserRepository;

/**
 * The bell (signed in), push settings and the app's push token, announcements (admins), and the daily trip-reminder
 * run. That run is public: it only sends the reminders due today, once each, so calling it again does nothing; the
 * daily GitHub Actions job calls it to wake the backend (Cloud Run gives it no CPU between requests).
 */
@RestController
public class NotificationController {

	/** The reminder run, for everyone together: the daily job plus a few manual runs. */
	private static final RateLimitProperties.Limit REMINDER_RUNS = new RateLimitProperties.Limit(6,
			Duration.ofHours(1));

	private final NotificationService notifications;

	private final UserRepository users;

	private final RateLimiter rateLimiter;

	public NotificationController(NotificationService notifications, UserRepository users, RateLimiter rateLimiter) {
		this.notifications = notifications;
		this.users = users;
		this.rateLimiter = rateLimiter;
	}

	/** Marks these ids read; without ids, everything. */
	public record ReadRequest(@Size(max = 100) List<Long> ids) {
	}

	/** An app install's Firebase Cloud Messaging token. */
	public record DeviceRequest(@NotBlank @Size(max = 4096) String token,
			@Pattern(regexp = "android|ios") String platform) {
	}

	public record AnnouncementRequest(@NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 500) String text) {
	}

	@GetMapping("/api/notifications")
	public NotificationService.Page list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) Long before) {
		return this.notifications.list(userId(jwt), before);
	}

	@GetMapping("/api/notifications/unread")
	public Map<String, Integer> unread(@AuthenticationPrincipal Jwt jwt) {
		return Map.of("unread", this.notifications.unread(userId(jwt)));
	}

	@PostMapping("/api/notifications/read")
	public Map<String, Integer> read(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody(required = false) ReadRequest body) {
		List<Long> ids = (body != null) ? body.ids() : null;
		return Map.of("unread", this.notifications.markRead(userId(jwt), ids));
	}

	@GetMapping("/api/notifications/settings")
	public NotificationService.Preferences settings(@AuthenticationPrincipal Jwt jwt) {
		return this.notifications.preferences(userId(jwt));
	}

	@PutMapping("/api/notifications/settings")
	public NotificationService.Preferences saveSettings(@AuthenticationPrincipal Jwt jwt,
			@RequestBody NotificationService.Preferences body) {
		return this.notifications.savePreferences(userId(jwt), body);
	}

	/** The app registers its push token after sign-in (and whenever Firebase gives it a new one). */
	@PutMapping("/api/notifications/devices")
	public ResponseEntity<Void> registerDevice(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody DeviceRequest body) {
		this.notifications.registerDevice(userId(jwt), body.token(),
				(body.platform() != null) ? body.platform() : "android");
		return ResponseEntity.noContent().build();
	}

	/** The app removes its token before signing out, so the next person on the phone gets no pushes for you. */
	@PostMapping("/api/notifications/devices/remove")
	public ResponseEntity<Void> removeDevice(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody DeviceRequest body) {
		this.notifications.removeDevice(userId(jwt), body.token());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/notifications/reminders")
	public Map<String, Integer> sendReminders() {
		this.rateLimiter.consume("notification-reminders", "all", REMINDER_RUNS);
		return Map.of("sent", this.notifications.sendTripReminders());
	}

	@PostMapping("/api/admin/announcements")
	public Map<String, Integer> announce(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody AnnouncementRequest body) {
		boolean admin = this.users.findById(userId(jwt)).map(User::isAdmin).orElse(false);
		if (!admin) {
			throw new ApiException(HttpStatus.FORBIDDEN, "not_admin", "Only GoAwayGo admins can do this.");
		}
		return Map.of("sent", this.notifications.announce(body.title(), body.text()));
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
