package com.goawaygo.backend.wanderbook;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Wanderbook photos, posts, likes and comments (V7 migration). Every read takes the viewer (null when signed out)
 * and only returns what they may see: see {@link #VISIBLE}.
 */
@Repository
public class PostRepository {

	/**
	 * The author of {@code p} is visible to {@code :viewer}: not waiting for their account to be deleted, nobody
	 * blocked anyone, and the profile is public, or is the viewer's own, or the viewer is an approved follower.
	 */
	static final String VISIBLE = """
			NOT EXISTS (SELECT 1 FROM users leaving WHERE leaving.id = p.author_id
			            AND leaving.deletion_requested_at IS NOT NULL)
			AND NOT EXISTS (SELECT 1 FROM wanderbook_blocks b
			            WHERE (b.blocker_id = :viewer AND b.blocked_id = p.author_id)
			               OR (b.blocker_id = p.author_id AND b.blocked_id = :viewer))
			AND (NOT pr.is_private OR p.author_id = :viewer
			     OR EXISTS (SELECT 1 FROM wanderbook_follows f
			                WHERE f.follower_id = :viewer AND f.followee_id = p.author_id AND f.status = 'accepted'))""";

	private final JdbcTemplate jdbc;

	private final NamedParameterJdbcTemplate named;

	public PostRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
		this.jdbc = jdbc;
		this.named = named;
	}

	// ---- photos ----

	record PhotoRow(long id, String publicId, long ownerId, Long postId, String objectKey, String contentType,
			long bytes, int width, int height) {
	}

	private static final RowMapper<PhotoRow> PHOTO = (rs, row) -> new PhotoRow(rs.getLong("id"),
			rs.getString("public_id"), rs.getLong("owner_id"), (Long) rs.getObject("post_id"),
			rs.getString("object_key"), rs.getString("content_type"), rs.getLong("bytes"), rs.getInt("width"),
			rs.getInt("height"));

	long storedBytes() {
		Long bytes = this.jdbc.queryForObject("SELECT coalesce(sum(bytes), 0) FROM wanderbook_photos", Long.class);
		return (bytes != null) ? bytes : 0;
	}

	void insertPhoto(String publicId, long ownerId, String objectKey, String contentType, long bytes, int width,
			int height) {
		this.jdbc.update("""
				INSERT INTO wanderbook_photos (public_id, owner_id, object_key, content_type, bytes, width, height)
				VALUES (?, ?, ?, ?, ?, ?, ?)""", publicId, ownerId, objectKey, contentType, bytes, width, height);
	}

	/** The owner's photos with these public ids that aren't used yet (not in a post, not a profile photo). */
	List<PhotoRow> unusedPhotos(long ownerId, Collection<String> publicIds) {
		if (publicIds.isEmpty()) {
			return List.of();
		}
		return this.named.query("""
				SELECT * FROM wanderbook_photos ph
				WHERE ph.owner_id = :owner AND ph.public_id IN (:ids) AND ph.post_id IS NULL
				  AND NOT EXISTS (SELECT 1 FROM wanderbook_profiles pr
				                  WHERE pr.avatar_photo_id = ph.id OR pr.cover_photo_id = ph.id)""",
				new MapSqlParameterSource("owner", ownerId).addValue("ids", publicIds), PHOTO);
	}

	void attachPhotos(long postId, List<PhotoRow> photos) {
		for (int i = 0; i < photos.size(); i++) {
			this.jdbc.update("UPDATE wanderbook_photos SET post_id = ?, position = ? WHERE id = ?", postId, i,
					photos.get(i).id());
		}
	}

	List<PhotoRow> photosOfPost(long postId) {
		return this.jdbc.query("SELECT * FROM wanderbook_photos WHERE post_id = ? ORDER BY position", PHOTO, postId);
	}

	Optional<PhotoRow> photoById(Long id) {
		if (id == null) {
			return Optional.empty();
		}
		return this.jdbc.query("SELECT * FROM wanderbook_photos WHERE id = ?", PHOTO, id).stream().findFirst();
	}

	void deletePhoto(long id) {
		this.jdbc.update("DELETE FROM wanderbook_photos WHERE id = ?", id);
	}

	/** Uploads never used within a day (the app was closed mid-post, ...). */
	List<PhotoRow> abandonedPhotos(Instant olderThan, int limit) {
		return this.jdbc.query("""
				SELECT * FROM wanderbook_photos ph
				WHERE ph.post_id IS NULL AND ph.created_at < ?
				  AND NOT EXISTS (SELECT 1 FROM wanderbook_profiles pr
				                  WHERE pr.avatar_photo_id = ph.id OR pr.cover_photo_id = ph.id)
				ORDER BY ph.created_at LIMIT ?""", PHOTO, Timestamp.from(olderThan), limit);
	}

	// ---- posts ----

	/** A post as stored, with its counts for the viewer. */
	record PostRow(long id, String publicId, long authorId, String authorPublicId, String authorName,
			String authorPhotoUrl, boolean authorPrivate, String caption, String placeName, String placeRegion,
			String placeCountry, Instant createdAt, Instant editedAt, long likes, long comments, boolean liked) {
	}

	private static final String POST_SELECT = """
			SELECT p.id, p.public_id, p.author_id, pr.public_id AS author_public_id, u.name AS author_name,
			       u.profile_picture_url AS author_photo_url, pr.is_private AS author_private, p.caption, p.place_name,
			       p.place_region, p.place_country, p.created_at, p.edited_at,
			       (SELECT count(*) FROM wanderbook_likes l WHERE l.post_id = p.id) AS likes,
			       (SELECT count(*) FROM wanderbook_comments c WHERE c.post_id = p.id) AS comments,
			       EXISTS (SELECT 1 FROM wanderbook_likes l WHERE l.post_id = p.id AND l.user_id = :viewer) AS liked
			FROM wanderbook_posts p
			JOIN wanderbook_profiles pr ON pr.user_id = p.author_id
			JOIN users u ON u.id = p.author_id
			""";

	private static final RowMapper<PostRow> POST = (rs, row) -> new PostRow(rs.getLong("id"),
			rs.getString("public_id"), rs.getLong("author_id"), rs.getString("author_public_id"),
			rs.getString("author_name"), rs.getString("author_photo_url"), rs.getBoolean("author_private"),
			rs.getString("caption"), rs.getString("place_name"), rs.getString("place_region"),
			rs.getString("place_country"), rs.getTimestamp("created_at").toInstant(),
			(rs.getTimestamp("edited_at") != null) ? rs.getTimestamp("edited_at").toInstant() : null,
			rs.getLong("likes"), rs.getLong("comments"), rs.getBoolean("liked"));

	long insertPost(long authorId, String caption, String placeName, String placeRegion, String placeCountry) {
		Long id = this.jdbc.queryForObject("""
				INSERT INTO wanderbook_posts (author_id, caption, place_name, place_region, place_country)
				VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, authorId, caption, placeName, placeRegion,
				placeCountry);
		return (id != null) ? id : 0;
	}

	void updatePost(long postId, String caption, String placeName, String placeRegion, String placeCountry) {
		this.jdbc.update("""
				UPDATE wanderbook_posts SET caption = ?, place_name = ?, place_region = ?, place_country = ?,
				                            edited_at = now()
				WHERE id = ?""", caption, placeName, placeRegion, placeCountry, postId);
	}

	void deletePost(long postId) {
		this.jdbc.update("DELETE FROM wanderbook_posts WHERE id = ?", postId);
	}

	/** The post if it exists and the viewer may see it. */
	Optional<PostRow> visiblePost(String publicId, Long viewerId) {
		return this.named
			.query(POST_SELECT + "WHERE p.public_id = :id AND " + VISIBLE, params(viewerId).addValue("id", publicId),
					POST)
			.stream()
			.findFirst();
	}

	/** By internal id, without visibility checks (admin, reports). */
	Optional<PostRow> postById(long id) {
		return this.named.query(POST_SELECT + "WHERE p.id = :id", params(null).addValue("id", id), POST)
			.stream()
			.findFirst();
	}

	/** The shared feed (decided for 4.2): everyone's newest posts the viewer may see. */
	List<PostRow> feed(Long viewerId, Long before, int limit) {
		return this.named.query(POST_SELECT + """
				WHERE (CAST(:before AS bigint) IS NULL OR p.id < :before) AND %s
				ORDER BY p.id DESC LIMIT :limit""".formatted(VISIBLE),
				params(viewerId).addValue("before", before).addValue("limit", limit), POST);
	}

	/** One person's posts, newest first (the caller checked they may be seen). */
	List<PostRow> postsBy(long authorId, Long viewerId, Long before, int limit) {
		return this.named.query(POST_SELECT + """
				WHERE p.author_id = :author AND (CAST(:before AS bigint) IS NULL OR p.id < :before) AND %s
				ORDER BY p.id DESC LIMIT :limit""".formatted(VISIBLE),
				params(viewerId).addValue("author", authorId).addValue("before", before).addValue("limit", limit),
				POST);
	}

	long postCount(long authorId) {
		Long count = this.jdbc.queryForObject("SELECT count(*) FROM wanderbook_posts WHERE author_id = ?", Long.class,
				authorId);
		return (count != null) ? count : 0;
	}

	/** Photos of several posts at once, in order, by post id. */
	Map<Long, List<PhotoRow>> photosOf(Collection<Long> postIds) {
		Map<Long, List<PhotoRow>> photos = new LinkedHashMap<>();
		if (postIds.isEmpty()) {
			return photos;
		}
		this.named
			.query("SELECT * FROM wanderbook_photos WHERE post_id IN (:ids) ORDER BY post_id, position",
					new MapSqlParameterSource("ids", postIds), PHOTO)
			.forEach(photo -> photos.computeIfAbsent(photo.postId(), id -> new ArrayList<>()).add(photo));
		return photos;
	}

	// ---- likes ----

	/** True if this is a new like. */
	boolean like(long postId, long userId) {
		return this.jdbc.update(
				"INSERT INTO wanderbook_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", postId,
				userId) > 0;
	}

	void unlike(long postId, long userId) {
		this.jdbc.update("DELETE FROM wanderbook_likes WHERE post_id = ? AND user_id = ?", postId, userId);
	}

	/** Who liked a post (newest first), without people blocked either way by the viewer. */
	List<WanderbookRepository.ProfileRow> likers(long postId, Long viewerId, int limit) {
		return this.named.query("""
				SELECT pr.user_id, pr.public_id, u.name, u.profile_picture_url, pr.bio, pr.home_town, pr.is_private
				FROM wanderbook_likes l
				JOIN wanderbook_profiles pr ON pr.user_id = l.user_id JOIN users u ON u.id = l.user_id
				WHERE l.post_id = :post AND u.deletion_requested_at IS NULL AND NOT EXISTS (SELECT 1 FROM wanderbook_blocks b
				      WHERE (b.blocker_id = :viewer AND b.blocked_id = l.user_id)
				         OR (b.blocker_id = l.user_id AND b.blocked_id = :viewer))
				ORDER BY l.created_at DESC LIMIT :limit""",
				params(viewerId).addValue("post", postId).addValue("limit", limit),
				(rs, row) -> new WanderbookRepository.ProfileRow(rs.getLong("user_id"), rs.getString("public_id"),
						rs.getString("name"), rs.getString("profile_picture_url"), rs.getString("bio"),
						rs.getString("home_town"), rs.getBoolean("is_private")));
	}

	// ---- comments ----

	record CommentRow(long id, long postId, long authorId, String authorPublicId, String authorName,
			String authorPhotoUrl, String text, Instant createdAt) {
	}

	private static final String COMMENT_SELECT = """
			SELECT c.id, c.post_id, c.author_id, pr.public_id AS author_public_id, u.name AS author_name,
			       u.profile_picture_url AS author_photo_url, c.text, c.created_at
			FROM wanderbook_comments c
			JOIN wanderbook_profiles pr ON pr.user_id = c.author_id
			JOIN users u ON u.id = c.author_id
			""";

	private static final RowMapper<CommentRow> COMMENT = (rs, row) -> new CommentRow(rs.getLong("id"),
			rs.getLong("post_id"), rs.getLong("author_id"), rs.getString("author_public_id"),
			rs.getString("author_name"), rs.getString("author_photo_url"), rs.getString("text"),
			rs.getTimestamp("created_at").toInstant());

	long insertComment(long postId, long authorId, String text) {
		Long id = this.jdbc.queryForObject(
				"INSERT INTO wanderbook_comments (post_id, author_id, text) VALUES (?, ?, ?) RETURNING id", Long.class,
				postId, authorId, text);
		return (id != null) ? id : 0;
	}

	Optional<CommentRow> comment(long id) {
		return this.named.query(COMMENT_SELECT + "WHERE c.id = :id", new MapSqlParameterSource("id", id), COMMENT)
			.stream()
			.findFirst();
	}

	/** Comments oldest first, without people blocked either way by the viewer. */
	List<CommentRow> comments(long postId, Long viewerId, Long after, int limit) {
		return this.named.query(COMMENT_SELECT + """
				WHERE c.post_id = :post AND (CAST(:after AS bigint) IS NULL OR c.id > :after)
				  AND u.deletion_requested_at IS NULL
				  AND NOT EXISTS (SELECT 1 FROM wanderbook_blocks b
				                  WHERE (b.blocker_id = :viewer AND b.blocked_id = c.author_id)
				                     OR (b.blocker_id = c.author_id AND b.blocked_id = :viewer))
				ORDER BY c.id LIMIT :limit""",
				params(viewerId).addValue("post", postId).addValue("after", after).addValue("limit", limit), COMMENT);
	}

	void deleteComment(long id) {
		this.jdbc.update("DELETE FROM wanderbook_comments WHERE id = ?", id);
	}

	private static MapSqlParameterSource params(Long viewerId) {
		return new MapSqlParameterSource().addValue("viewer", viewerId, Types.BIGINT);
	}

}
