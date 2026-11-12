package com.goawaygo.backend.wanderbook;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Wanderbook profiles, follows and blocks (V6 migration). Every account has a profile (a database trigger creates
 * it with the account). Visibility rules live in {@link WanderbookService}; this class only reads and writes.
 */
@Repository
public class WanderbookRepository {

	static final String ACCEPTED = "accepted";

	static final String PENDING = "pending";

	/** The account in column {@code %s} isn't waiting to be deleted (hidden from others meanwhile). */
	private static final String NOT_LEAVING = "%s NOT IN (SELECT id FROM users WHERE deletion_requested_at IS NOT NULL)";

	private final JdbcTemplate jdbc;

	public WanderbookRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A profile with its account name and photo. */
	record ProfileRow(long userId, String publicId, String name, String photoUrl, String bio, String homeTown,
			boolean isPrivate) {
	}

	private static final String PROFILE_COLUMNS = """
			p.user_id, p.public_id, u.name, u.profile_picture_url, p.bio, p.home_town, p.is_private
			FROM wanderbook_profiles p JOIN users u ON u.id = p.user_id""";

	private static final RowMapper<ProfileRow> PROFILE = (rs, row) -> new ProfileRow(rs.getLong("user_id"),
			rs.getString("public_id"), rs.getString("name"), rs.getString("profile_picture_url"), rs.getString("bio"),
			rs.getString("home_town"), rs.getBoolean("is_private"));

	Optional<ProfileRow> byUserId(long userId) {
		return this.jdbc.query("SELECT " + PROFILE_COLUMNS + " WHERE p.user_id = ?", PROFILE, userId).stream().findFirst();
	}

	/** Someone's profile; none while their account waits to be deleted. */
	Optional<ProfileRow> byPublicId(String publicId) {
		return this.jdbc.query("SELECT " + PROFILE_COLUMNS + " WHERE p.public_id = ? AND u.deletion_requested_at IS NULL",
				PROFILE, publicId)
			.stream()
			.findFirst();
	}

	void update(long userId, String bio, String homeTown, boolean isPrivate) {
		this.jdbc.update("""
				UPDATE wanderbook_profiles SET bio = ?, home_town = ?, is_private = ?, updated_at = now()
				WHERE user_id = ?""", bio, homeTown, isPrivate, userId);
	}

	// ---- follows ----

	/** {@link #ACCEPTED}, {@link #PENDING} or null. */
	String followStatus(long followerId, long followeeId) {
		return this.jdbc
			.queryForList("SELECT status FROM wanderbook_follows WHERE follower_id = ? AND followee_id = ?",
					String.class, followerId, followeeId)
			.stream()
			.findFirst()
			.orElse(null);
	}

	/** Adds a follow (or request); an existing one is left as it is. */
	/** True if this is a new follow or request. */
	boolean follow(long followerId, long followeeId, String status) {
		return this.jdbc.update("""
				INSERT INTO wanderbook_follows (follower_id, followee_id, status) VALUES (?, ?, ?)
				ON CONFLICT (follower_id, followee_id) DO NOTHING""", followerId, followeeId, status) > 0;
	}

	/** True if a request was waiting (and is now accepted). */
	boolean approve(long followerId, long followeeId) {
		return this.jdbc.update("""
				UPDATE wanderbook_follows SET status = 'accepted', created_at = now()
				WHERE follower_id = ? AND followee_id = ? AND status = 'pending'""", followerId, followeeId) > 0;
	}

	void approveAllRequests(long followeeId) {
		this.jdbc.update("""
				UPDATE wanderbook_follows SET status = 'accepted', created_at = now()
				WHERE followee_id = ? AND status = 'pending'""", followeeId);
	}

	/** Removes a follow or request. True if there was one. */
	boolean unfollow(long followerId, long followeeId) {
		return this.jdbc.update("DELETE FROM wanderbook_follows WHERE follower_id = ? AND followee_id = ?", followerId,
				followeeId) > 0;
	}

	long followerCount(long userId) {
		return count("SELECT count(*) FROM wanderbook_follows WHERE followee_id = ? AND status = 'accepted' AND "
				+ NOT_LEAVING.formatted("follower_id"), userId);
	}

	long followingCount(long userId) {
		return count("SELECT count(*) FROM wanderbook_follows WHERE follower_id = ? AND status = 'accepted' AND "
				+ NOT_LEAVING.formatted("followee_id"), userId);
	}

	long requestCount(long userId) {
		return count("SELECT count(*) FROM wanderbook_follows WHERE followee_id = ? AND status = 'pending' AND "
				+ NOT_LEAVING.formatted("follower_id"), userId);
	}

	/** One person in a list, with the follow row's id as the paging cursor. */
	record ListedProfile(long cursor, ProfileRow profile) {
	}

	private static final RowMapper<ListedProfile> LISTED = (rs, row) -> new ListedProfile(rs.getLong("cursor"),
			PROFILE.mapRow(rs, row));

	/**
	 * People on the other end of {@code userId}'s follows, newest first, without anyone blocked either way by
	 * {@code viewerId} (when signed in).
	 * @param followers true: who follows {@code userId}; false: whom {@code userId} follows
	 * @param status {@link #ACCEPTED} or {@link #PENDING} (requests)
	 */
	List<ListedProfile> follows(long userId, boolean followers, String status, Long viewerId, Long before, int limit) {
		String self = followers ? "followee_id" : "follower_id";
		String other = followers ? "follower_id" : "followee_id";
		return this.jdbc.query("SELECT f.id AS cursor, " + PROFILE_COLUMNS.replace("FROM wanderbook_profiles p",
				"FROM wanderbook_follows f JOIN wanderbook_profiles p ON p.user_id = f." + other) + """

				WHERE f.%s = ? AND f.status = ? AND (?::bigint IS NULL OR f.id < ?::bigint)
				  AND u.deletion_requested_at IS NULL
				  AND NOT EXISTS (SELECT 1 FROM wanderbook_blocks b
				                  WHERE (b.blocker_id = ? AND b.blocked_id = p.user_id)
				                     OR (b.blocker_id = p.user_id AND b.blocked_id = ?))
				ORDER BY f.id DESC LIMIT ?""".formatted(self), LISTED, userId, status, before, before, viewerId,
				viewerId, limit);
	}

	// ---- blocks ----

	/** Blocks {@code blockedId} and removes follows and requests both ways. */
	void block(long blockerId, long blockedId) {
		this.jdbc.update("""
				INSERT INTO wanderbook_blocks (blocker_id, blocked_id) VALUES (?, ?)
				ON CONFLICT (blocker_id, blocked_id) DO NOTHING""", blockerId, blockedId);
		this.jdbc.update("""
				DELETE FROM wanderbook_follows
				WHERE (follower_id = ? AND followee_id = ?) OR (follower_id = ? AND followee_id = ?)""", blockerId,
				blockedId, blockedId, blockerId);
	}

	void unblock(long blockerId, long blockedId) {
		this.jdbc.update("DELETE FROM wanderbook_blocks WHERE blocker_id = ? AND blocked_id = ?", blockerId, blockedId);
	}

	boolean blocked(long blockerId, long blockedId) {
		return count("SELECT count(*) FROM wanderbook_blocks WHERE blocker_id = ? AND blocked_id = ?", blockerId,
				blockedId) > 0;
	}

	/** Whom {@code userId} blocked, newest first. */
	List<ProfileRow> blockedBy(long userId) {
		return this.jdbc.query("SELECT " + PROFILE_COLUMNS.replace("FROM wanderbook_profiles p",
				"FROM wanderbook_blocks b JOIN wanderbook_profiles p ON p.user_id = b.blocked_id") + """

				WHERE b.blocker_id = ? ORDER BY b.created_at DESC LIMIT 500""", PROFILE, userId);
	}

	// ---- people search ----

	/** People whose name contains {@code text} (names starting with it first), without blocked ones either way. */
	List<ProfileRow> search(String text, Long viewerId, int limit) {
		String pattern = "%" + text.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
			.replace("_", "\\_") + "%";
		String prefix = pattern.substring(1);
		return this.jdbc.query("SELECT " + PROFILE_COLUMNS + """

				WHERE lower(u.name) LIKE ? AND u.deletion_requested_at IS NULL
				  AND NOT EXISTS (SELECT 1 FROM wanderbook_blocks b
				                  WHERE (b.blocker_id = ? AND b.blocked_id = p.user_id)
				                     OR (b.blocker_id = p.user_id AND b.blocked_id = ?))
				ORDER BY (lower(u.name) LIKE ?) DESC, u.name, p.user_id LIMIT ?""", PROFILE, pattern, viewerId,
				viewerId, prefix, limit);
	}

	private long count(String sql, Object... args) {
		Long value = this.jdbc.queryForObject(sql, Long.class, args);
		return (value != null) ? value : 0;
	}

}
