package com.goawaygo.backend.notifications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code notifications}, {@code notification_devices} and {@code notification_settings} tables (V9). */
@Repository
class Notifications {

	private final JdbcTemplate jdbc;

	Notifications(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ---- notifications ----

	/** A notification as the bell shows it, with the person, post and plan it points to. */
	record Row(long id, String type, int count, String title, String text, Instant readAt, Instant createdAt,
			String actorPublicId, String actorName, String actorPhotoUrl, String postPublicId, String planPublicId,
			String planPlace, String planTitle) {
	}

	private static final String SELECT = """
			SELECT n.id, n.type, n.count, n.title, n.text, n.read_at, n.created_at,
			       ap.public_id AS actor_public_id, a.name AS actor_name, a.profile_picture_url AS actor_photo_url,
			       p.public_id AS post_public_id,
			       tp.public_id AS plan_public_id, tp.place_name AS plan_place, tp.itinerary ->> 'title' AS plan_title
			FROM notifications n
			LEFT JOIN users a ON a.id = n.actor_id
			LEFT JOIN wanderbook_profiles ap ON ap.user_id = n.actor_id
			LEFT JOIN wanderbook_posts p ON p.id = n.post_id
			LEFT JOIN trip_plans tp ON tp.id = n.plan_id
			""";

	/** Hides notifications from someone either side has blocked since, or whose account waits to be deleted. */
	private static final String NOT_BLOCKED = """
			(n.actor_id IS NULL OR NOT EXISTS (SELECT 1 FROM wanderbook_blocks b
			    WHERE (b.blocker_id = n.user_id AND b.blocked_id = n.actor_id)
			       OR (b.blocker_id = n.actor_id AND b.blocked_id = n.user_id))
			    AND n.actor_id NOT IN (SELECT id FROM users WHERE deletion_requested_at IS NOT NULL))""";

	long insert(long userId, String type, Long actorId, Long postId, Long commentId, Long planId, String title,
			String text) {
		Long id = this.jdbc.queryForObject("""
				INSERT INTO notifications (user_id, type, actor_id, post_id, comment_id, plan_id, title, text)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, userId, type, actorId, postId, commentId,
				planId, title, text);
		return (id != null) ? id : 0;
	}

	/** An unread "liked your post" after adding a like to it: its id, how many likes, and when it last pushed. */
	record Like(long id, int count, Instant pushedAt) {
	}

	/**
	 * Adds a like to the post's unread "liked your post", or starts one. It is written again as a new row, so the
	 * newest activity is always at the top of the bell.
	 */
	Like addLike(long userId, long postId, long actorId) {
		List<Like> previous = this.jdbc.query("""
				DELETE FROM notifications WHERE user_id = ? AND post_id = ? AND type = 'like' AND read_at IS NULL
				RETURNING id, count, pushed_at""", (rs, i) -> new Like(rs.getLong("id"), rs.getInt("count"),
				instant(rs, "pushed_at")), userId, postId);
		int count = previous.isEmpty() ? 1 : previous.get(0).count() + 1;
		Instant pushedAt = previous.isEmpty() ? null : previous.get(0).pushedAt();
		Long id = this.jdbc.queryForObject("""
				INSERT INTO notifications (user_id, type, actor_id, post_id, count, pushed_at)
				VALUES (?, 'like', ?, ?, ?, ?) RETURNING id""", Long.class, userId, actorId, postId, count,
				(pushedAt != null) ? Timestamp.from(pushedAt) : null);
		return new Like((id != null) ? id : 0, count, pushedAt);
	}

	void markPushed(long id) {
		this.jdbc.update("UPDATE notifications SET pushed_at = now() WHERE id = ?", id);
	}

	/** Removes unread notifications of these types that {@code actorId} caused (e.g. a cancelled follow request). */
	void withdraw(long userId, long actorId, String... types) {
		this.jdbc.update("""
				DELETE FROM notifications
				WHERE user_id = ? AND actor_id = ? AND read_at IS NULL AND type = ANY (?)""", userId, actorId, types);
	}

	List<Row> list(long userId, Long before, int limit) {
		return this.jdbc.query(SELECT + """
				WHERE n.user_id = ? AND (?::bigint IS NULL OR n.id < ?::bigint) AND\s""" + NOT_BLOCKED + """

				ORDER BY n.id DESC LIMIT ?""", this::row, userId, before, before, limit);
	}

	Optional<Row> find(long id) {
		return this.jdbc.query(SELECT + "WHERE n.id = ?", this::row, id).stream().findFirst();
	}

	int unread(long userId) {
		Integer count = this.jdbc.queryForObject("SELECT count(*) FROM notifications n WHERE n.user_id = ? "
				+ "AND n.read_at IS NULL AND " + NOT_BLOCKED, Integer.class, userId);
		return (count != null) ? count : 0;
	}

	void markRead(long userId, List<Long> ids) {
		if (ids == null) {
			this.jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL",
					userId);
			return;
		}
		this.jdbc.update("""
				UPDATE notifications SET read_at = now()
				WHERE user_id = ? AND read_at IS NULL AND id = ANY (?)""", userId, ids.toArray(Long[]::new));
	}

	int deleteOlderThan(Instant cutoff) {
		return this.jdbc.update("DELETE FROM notifications WHERE created_at < ?", Timestamp.from(cutoff));
	}

	/** Everyone's id, for an announcement. */
	List<Long> allUsers() {
		return this.jdbc.queryForList("SELECT id FROM users WHERE deletion_requested_at IS NULL ORDER BY id", Long.class);
	}

	// ---- settings ----

	record Settings(boolean wanderbook, boolean tripReminders, boolean sharedPlans, boolean announcements) {

		static final Settings ALL = new Settings(true, true, true, true);

		boolean allows(Setting setting) {
			return switch (setting) {
				case WANDERBOOK -> this.wanderbook;
				case TRIP_REMINDERS -> this.tripReminders;
				case SHARED_PLANS -> this.sharedPlans;
				case ANNOUNCEMENTS -> this.announcements;
			};
		}

	}

	/** The groups of pushes a user can turn off. */
	enum Setting {

		WANDERBOOK, TRIP_REMINDERS, SHARED_PLANS, ANNOUNCEMENTS

	}

	Settings settings(long userId) {
		return this.jdbc.query("""
				SELECT wanderbook, trip_reminders, shared_plans, announcements
				FROM notification_settings WHERE user_id = ?""",
				(rs, i) -> new Settings(rs.getBoolean("wanderbook"), rs.getBoolean("trip_reminders"),
						rs.getBoolean("shared_plans"), rs.getBoolean("announcements")),
				userId)
			.stream()
			.findFirst()
			.orElse(Settings.ALL);
	}

	void saveSettings(long userId, Settings settings) {
		this.jdbc.update("""
				INSERT INTO notification_settings (user_id, wanderbook, trip_reminders, shared_plans, announcements)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (user_id) DO UPDATE SET wanderbook = EXCLUDED.wanderbook,
				    trip_reminders = EXCLUDED.trip_reminders, shared_plans = EXCLUDED.shared_plans,
				    announcements = EXCLUDED.announcements, updated_at = now()""", userId, settings.wanderbook(),
				settings.tripReminders(), settings.sharedPlans(), settings.announcements());
	}

	// ---- devices ----

	/** Registers an app install's token for this user (moving it from whoever used the phone before). */
	void saveDevice(long userId, String token, String platform) {
		this.jdbc.update("""
				INSERT INTO notification_devices (token, user_id, platform) VALUES (?, ?, ?)
				ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
				    updated_at = now()""", token, userId, platform);
	}

	void removeDevice(long userId, String token) {
		this.jdbc.update("DELETE FROM notification_devices WHERE user_id = ? AND token = ?", userId, token);
	}

	void forgetDevice(String token) {
		this.jdbc.update("DELETE FROM notification_devices WHERE token = ?", token);
	}

	List<String> devices(long userId) {
		return this.jdbc.queryForList("SELECT token FROM notification_devices WHERE user_id = ? ORDER BY updated_at DESC",
				String.class, userId);
	}

	// ---- trip plans ----

	/** True (once a day per plan) when the owner should hear that someone opened their shared plan. */
	boolean claimPlanOpened(long planId) {
		return this.jdbc.update("""
				UPDATE trip_plans SET opened_notified_at = now()
				WHERE id = ? AND (opened_notified_at IS NULL OR opened_notified_at < now() - interval '1 day')""",
				planId) > 0;
	}

	/** A trip that starts in {@code daysLeft} days and whose owner hasn't had this reminder yet. */
	record DueTrip(long planId, long userId, String place, int daysLeft) {
	}

	/**
	 * Marks and returns the trips due a reminder today: "in N days" for trips 2-3 days away that had none yet, and
	 * "tomorrow" for trips a day away.
	 */
	List<DueTrip> claimDueTrips(LocalDate today) {
		return this.jdbc.query("""
				UPDATE trip_plans SET reminded_days = start_date - ?::date
				WHERE ((start_date - ?::date = 1 AND (reminded_days IS NULL OR reminded_days > 1))
				    OR (start_date - ?::date BETWEEN 2 AND 3 AND reminded_days IS NULL))
				  AND user_id NOT IN (SELECT id FROM users WHERE deletion_requested_at IS NOT NULL)
				RETURNING id, user_id, place_name, reminded_days""",
				(rs, i) -> new DueTrip(rs.getLong("id"), rs.getLong("user_id"), rs.getString("place_name"),
						rs.getInt("reminded_days")),
				today, today, today);
	}

	private Row row(ResultSet rs, int i) throws SQLException {
		return new Row(rs.getLong("id"), rs.getString("type"), rs.getInt("count"), rs.getString("title"),
				rs.getString("text"), instant(rs, "read_at"), instant(rs, "created_at"),
				rs.getString("actor_public_id"), rs.getString("actor_name"), rs.getString("actor_photo_url"),
				rs.getString("post_public_id"), rs.getString("plan_public_id"), rs.getString("plan_place"),
				rs.getString("plan_title"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return (value != null) ? value.toInstant() : null;
	}

}
