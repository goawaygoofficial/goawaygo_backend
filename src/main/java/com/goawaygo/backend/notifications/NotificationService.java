package com.goawaygo.backend.notifications;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.notifications.Notifications.Row;
import com.goawaygo.backend.notifications.Notifications.Setting;
import com.goawaygo.backend.notifications.Notifications.Settings;
import com.goawaygo.backend.notifications.PushSender.Push;

/**
 * Notifications (decided for 4.5): Wanderbook activity, trip reminders, someone opening a shared plan, and
 * announcements. Each one is saved for the bell (kept 90 days) and, unless its switch is off, pushed to the user's
 * phones. Nothing is sent for your own actions, and blocks hide notifications both ways.
 */
@Service
public class NotificationService {

	static final int PAGE_SIZE = 30;

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final Notifications notifications;

	private final PushSender push;

	private final NotificationProperties properties;

	NotificationService(Notifications notifications, PushSender push, NotificationProperties properties) {
		this.notifications = notifications;
		this.push = push;
		this.properties = properties;
	}

	/** Someone the notification is about, with their Wanderbook profile id. */
	public record Actor(String publicId, String name, String photoUrl) {
	}

	/**
	 * A notification in the bell.
	 *
	 * @param message the line to show ("Asha liked your post.")
	 * @param detail more text under it (a comment, an announcement), or empty
	 * @param postId the post it opens, if any
	 * @param planId the trip plan it opens, if any (profiles open from {@code actor})
	 */
	public record Item(long id, String type, String message, String detail, boolean read, Instant createdAt,
			Actor actor, String postId, String planId) {
	}

	public record Page(List<Item> items, Long nextCursor, int unread) {
	}

	/** Which pushes the user wants (the bell lists everything either way). */
	public record Preferences(boolean wanderbook, boolean tripReminders, boolean sharedPlans, boolean announcements) {
	}

	// ---- the bell ----

	public Page list(long userId, Long before) {
		List<Row> rows = this.notifications.list(userId, before, PAGE_SIZE);
		List<Item> items = rows.stream().map(NotificationService::item).toList();
		Long next = (rows.size() == PAGE_SIZE) ? rows.get(rows.size() - 1).id() : null;
		return new Page(items, next, this.notifications.unread(userId));
	}

	public int unread(long userId) {
		return this.notifications.unread(userId);
	}

	/** Marks these notifications read, or all of them when {@code ids} is null. */
	public int markRead(long userId, List<Long> ids) {
		this.notifications.markRead(userId, ids);
		return this.notifications.unread(userId);
	}

	// ---- settings and devices ----

	public Preferences preferences(long userId) {
		Settings settings = this.notifications.settings(userId);
		return new Preferences(settings.wanderbook(), settings.tripReminders(), settings.sharedPlans(),
				settings.announcements());
	}

	public Preferences savePreferences(long userId, Preferences preferences) {
		this.notifications.saveSettings(userId, new Settings(preferences.wanderbook(), preferences.tripReminders(),
				preferences.sharedPlans(), preferences.announcements()));
		return preferences(userId);
	}

	public void registerDevice(long userId, String token, String platform) {
		this.notifications.saveDevice(userId, token, platform);
	}

	public void removeDevice(long userId, String token) {
		this.notifications.removeDevice(userId, token);
	}

	// ---- Wanderbook ----

	/** {@code actorId} followed {@code userId}, or asked to ({@code request}, for a private profile). */
	public void followed(long actorId, long userId, boolean request) {
		String type = request ? "follow_request" : "follow";
		record(userId, type, actorId, null, null, null, "", "");
	}

	/** {@code actorId} unfollowed or cancelled a request: the unread notification about it goes. */
	public void unfollowed(long actorId, long userId) {
		this.notifications.withdraw(userId, actorId, "follow", "follow_request");
	}

	/** {@code actorId} accepted {@code userId}'s follow request. */
	public void followAccepted(long actorId, long userId) {
		this.notifications.withdraw(actorId, userId, "follow_request");
		record(userId, "follow_accepted", actorId, null, null, null, "", "");
	}

	/** Likes on one post add up in one unread notification, pushed at most hourly. */
	public void liked(long actorId, long postId, long authorId) {
		if (actorId == authorId) {
			return;
		}
		Notifications.Like like = this.notifications.addLike(authorId, postId, actorId);
		Instant last = like.pushedAt();
		if (last == null || last.isBefore(Instant.now().minus(this.properties.likePushEvery()))) {
			this.notifications.markPushed(like.id());
			queue(authorId, like.id());
		}
	}

	public void commented(long actorId, long postId, long commentId, long authorId, String text) {
		if (actorId == authorId) {
			return;
		}
		record(authorId, "comment", actorId, postId, commentId, null, "", text);
	}

	// ---- trip plans ----

	/** Someone other than the owner opened a shared plan (at most one notification a day per plan). */
	public void planOpened(long planId, long ownerId, Long viewerId) {
		if (viewerId != null && viewerId == ownerId) {
			return;
		}
		if (this.notifications.claimPlanOpened(planId)) {
			record(ownerId, "plan_opened", null, null, null, planId, "", "");
		}
	}

	/** Sends today's trip reminders ("in 3 days", "tomorrow"); safe to run any number of times. */
	@Transactional
	public int sendTripReminders() {
		LocalDate today = LocalDate.now(this.properties.reminderZone());
		List<Notifications.DueTrip> due = this.notifications.claimDueTrips(today);
		for (Notifications.DueTrip trip : due) {
			long id = this.notifications.insert(trip.userId(), "trip_reminder", null, null, null, trip.planId(), "",
					String.valueOf(trip.daysLeft()));
			queue(trip.userId(), id);
		}
		int deleted = this.notifications.deleteOlderThan(Instant.now().minus(this.properties.keep()));
		if (!due.isEmpty() || deleted > 0) {
			log.info("Trip reminders: {} sent, {} old notifications deleted", due.size(), deleted);
		}
		return due.size();
	}

	// ---- announcements ----

	/** A message from the team to everyone; returns how many accounts got it. */
	@Transactional
	public int announce(String title, String text) {
		String cleanTitle = clean(title, 120);
		String cleanText = clean(text, 500);
		if (cleanTitle.isEmpty() || cleanText.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_announcement",
					"An announcement needs a title and a message.");
		}
		List<Long> users = this.notifications.allUsers();
		for (long userId : users) {
			long id = this.notifications.insert(userId, "announcement", null, null, null, null, cleanTitle,
					cleanText);
			queue(userId, id);
		}
		return users.size();
	}

	// ---- writing and sending ----

	private void record(long userId, String type, Long actorId, Long postId, Long commentId, Long planId,
			String title, String text) {
		long id = this.notifications.insert(userId, type, actorId, postId, commentId, planId, title, text);
		queue(userId, id);
	}

	/** Pushes the notification as the bell will show it. */
	private void queue(long userId, long id) {
		this.notifications.find(id).ifPresent(row -> {
			Item item = item(row);
			Map<String, String> data = new LinkedHashMap<>();
			data.put("type", item.type());
			data.put("notificationId", String.valueOf(item.id()));
			if (item.actor() != null) {
				data.put("profileId", item.actor().publicId());
			}
			if (item.postId() != null) {
				data.put("postId", item.postId());
			}
			if (item.planId() != null) {
				data.put("planId", item.planId());
			}
			String body = item.detail().isEmpty() ? item.message() : item.message() + " " + item.detail();
			String heading = switch (row.type()) {
				case "trip_reminder" -> "Trip reminder";
				case "plan_opened" -> "Your shared trip plan";
				case "announcement" -> row.title();
				default -> "Wanderbook";
			};
			this.push.queue(new Push(userId, setting(row.type()), heading,
					"announcement".equals(row.type()) ? row.text() : body, data));
		});
	}

	private static Setting setting(String type) {
		return switch (type) {
			case "trip_reminder" -> Setting.TRIP_REMINDERS;
			case "plan_opened" -> Setting.SHARED_PLANS;
			case "announcement" -> Setting.ANNOUNCEMENTS;
			default -> Setting.WANDERBOOK;
		};
	}

	/** The bell's line for a notification, written now so a renamed person shows their new name. */
	static Item item(Row row) {
		String actor = (row.actorName() != null) ? row.actorName() : "Someone";
		String place = (row.planPlace() != null) ? row.planPlace() : "your trip";
		String message = switch (row.type()) {
			case "follow" -> actor + " started following you.";
			case "follow_request" -> actor + " asked to follow you.";
			case "follow_accepted" -> actor + " accepted your follow request.";
			case "like" -> (row.count() <= 1) ? actor + " liked your post."
					: actor + " and " + others(row.count() - 1) + " liked your post.";
			case "comment" -> actor + " commented on your post:";
			case "trip_reminder" -> "1".equals(row.text()) ? "Your trip to " + place + " starts tomorrow."
					: "Your trip to " + place + " starts in " + row.text() + " days.";
			case "plan_opened" -> "Someone opened your shared plan"
					+ ((row.planTitle() != null) ? " “" + row.planTitle() + "”." : ".");
			case "announcement" -> row.title();
			default -> "";
		};
		String detail = switch (row.type()) {
			case "comment", "announcement" -> row.text();
			default -> "";
		};
		Actor who = (row.actorPublicId() != null)
				? new Actor(row.actorPublicId(), row.actorName(), row.actorPhotoUrl()) : null;
		return new Item(row.id(), row.type(), message, detail, row.readAt() != null, row.createdAt(), who,
				row.postPublicId(), row.planPublicId());
	}

	private static String others(int count) {
		return (count == 1) ? "1 other" : count + " others";
	}

	private static String clean(String text, int max) {
		String value = (text == null) ? "" : text.strip();
		return (value.length() > max) ? value.substring(0, max) : value;
	}

}
