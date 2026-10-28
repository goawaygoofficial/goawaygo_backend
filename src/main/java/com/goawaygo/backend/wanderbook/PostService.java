package com.goawaygo.backend.wanderbook;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.discover.PlaceSearch;
import com.goawaygo.backend.media.MediaProperties;
import com.goawaygo.backend.media.PhotoFiles;
import com.goawaygo.backend.media.R2Storage;
import com.goawaygo.backend.moderation.ContentModeration;
import com.goawaygo.backend.wanderbook.PostRepository.CommentRow;
import com.goawaygo.backend.wanderbook.PostRepository.PhotoRow;
import com.goawaygo.backend.wanderbook.PostRepository.PostRow;
import com.goawaygo.backend.wanderbook.WanderbookService.Page;
import com.goawaygo.backend.wanderbook.WanderbookService.Person;

/**
 * Wanderbook photos, posts, likes and comments (decided for 4.2):
 * <ul>
 * <li>Photos are uploaded one at a time, kept as the original file (only its location data is removed), checked by
 * moderation, then stored in R2. The photo's GPS only suggests the place tag (names; never stored).</li>
 * <li>A post is 1 to 10 of the user's unused uploads, a caption and an optional place tag; text is checked too.</li>
 * <li>Who sees what follows the profile rules ({@link PostRepository#VISIBLE}).</li>
 * </ul>
 */
@Service
public class PostService {

	static final int MAX_PHOTOS = 10;

	static final int FEED_PAGE = 20;

	static final int COMMENTS_PAGE = 50;

	private static final Logger log = LoggerFactory.getLogger(PostService.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final PostRepository posts;

	private final WanderbookRepository profiles;

	private final R2Storage storage;

	private final MediaProperties media;

	private final ContentModeration moderation;

	private final PlaceSearch places;

	private final TransactionTemplate transactions;

	private final JdbcTemplate jdbc;

	public PostService(PostRepository posts, WanderbookRepository profiles, R2Storage storage, MediaProperties media,
			ContentModeration moderation, PlaceSearch places, TransactionTemplate transactions, JdbcTemplate jdbc) {
		this.posts = posts;
		this.profiles = profiles;
		this.storage = storage;
		this.media = media;
		this.moderation = moderation;
		this.places = places;
		this.transactions = transactions;
		this.jdbc = jdbc;
	}

	public record Photo(String id, String url, int width, int height) {

		Photo(PhotoRow row, MediaProperties media) {
			this(row.publicId(), media.publicUrl(row.objectKey()), row.width(), row.height());
		}

	}

	/** A place tag: names only. */
	public record PlaceTag(String name, String region, String country) {
	}

	/** An upload, with the place its location suggests (null if the photo had none). */
	public record Uploaded(Photo photo, PlaceTag suggestedPlace) {
	}

	public record Post(String publicId, Person author, String caption, PlaceTag place, List<Photo> photos,
			long likes, long comments, boolean likedByYou, boolean isYours, Instant createdAt, Instant editedAt) {
	}

	public record Comment(long id, Person author, String text, Instant createdAt, boolean canDelete) {
	}

	// ---- uploads ----

	/** Prepares, checks and stores one photo. Not visible to anyone until it is part of a post. */
	public Uploaded upload(long userId, byte[] file) {
		if (file.length == 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_photo", "The photo is empty.");
		}
		if (file.length > this.media.maxPhotoBytes()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "photo_too_large",
					"Photos can be up to %d MB.".formatted(this.media.maxPhotoBytes() / 1_000_000));
		}
		if (this.posts.storedBytes() + file.length > this.media.maxTotalBytes()) {
			log.error("Wanderbook photo storage is full ({} bytes stored)", this.posts.storedBytes());
			throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "storage_full",
					"Wanderbook can't take new photos right now. Please try again later.");
		}
		PhotoFiles.Prepared prepared;
		try {
			prepared = PhotoFiles.prepare(file);
		}
		catch (PhotoFiles.Rejected ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_photo", ex.getMessage());
		}
		this.moderation.checkImage(prepared.content(), prepared.type().contentType);
		PlaceTag suggested = null;
		if (prepared.latitude() != null) {
			PlaceSearch.PlaceName name = this.places.nameAt(prepared.latitude(), prepared.longitude());
			if (name != null) {
				suggested = new PlaceTag(name.name(), name.region(), name.country());
			}
		}
		String publicId = randomId();
		String key = "p/" + publicId + "." + prepared.type().extension;
		this.storage.put(key, prepared.content(), prepared.type().contentType);
		this.posts.insertPhoto(publicId, userId, key, prepared.type().contentType, prepared.content().length,
				prepared.width(), prepared.height());
		PhotoRow row = new PhotoRow(0, publicId, userId, null, key, prepared.type().contentType,
				prepared.content().length, prepared.width(), prepared.height());
		return new Uploaded(new Photo(row, this.media), suggested);
	}

	// ---- posts ----

	public Post create(long userId, List<String> photoIds, String caption, PlaceTag place) {
		LinkedHashSet<String> ids = new LinkedHashSet<>(photoIds);
		if (ids.isEmpty() || ids.size() > MAX_PHOTOS || ids.size() != photoIds.size()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_photos", "A post has 1 to 10 different photos.");
		}
		String text = clean(caption);
		PlaceTag tag = cleanPlace(place);
		this.moderation.checkText(textToCheck(text, tag));
		String publicId = this.transactions.execute(status -> {
			List<PhotoRow> found = this.posts.unusedPhotos(userId, ids);
			if (found.size() != ids.size()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_photos",
						"Some photos are missing or already used. Please add them again.");
			}
			Map<String, PhotoRow> byId = new java.util.HashMap<>();
			found.forEach(photo -> byId.put(photo.publicId(), photo));
			long postId = this.posts.insertPost(userId, text, name(tag), region(tag), country(tag));
			this.posts.attachPhotos(postId, ids.stream().map(byId::get).toList());
			return this.posts.postById(postId).orElseThrow().publicId();
		});
		return post(publicId, userId);
	}

	public Post post(String publicId, Long viewerId) {
		PostRow row = this.posts.visiblePost(publicId, viewerId).orElseThrow(PostService::postNotFound);
		return toPosts(List.of(row), viewerId).get(0);
	}

	/** Changes the caption and place tag (your own posts). */
	public Post edit(long userId, String publicId, String caption, PlaceTag place) {
		PostRow row = own(publicId, userId);
		String text = clean(caption);
		PlaceTag tag = cleanPlace(place);
		this.moderation.checkText(textToCheck(text, tag));
		this.posts.updatePost(row.id(), text, name(tag), region(tag), country(tag));
		return post(publicId, userId);
	}

	public void delete(long userId, String publicId) {
		deletePost(own(publicId, userId).id());
	}

	/** Deletes a post and its photo files (also used by admins). */
	void deletePost(long postId) {
		List<PhotoRow> photos = this.posts.photosOfPost(postId);
		this.posts.deletePost(postId);
		photos.forEach(photo -> this.storage.delete(photo.objectKey()));
	}

	public Page<Post> feed(Long viewerId, Long before) {
		return page(this.posts.feed(viewerId, before, FEED_PAGE), viewerId);
	}

	/** A profile's posts; the caller already checked the viewer may see them. */
	public Page<Post> postsBy(long authorId, Long viewerId, Long before) {
		return page(this.posts.postsBy(authorId, viewerId, before, FEED_PAGE), viewerId);
	}

	public long postCount(long authorId) {
		return this.posts.postCount(authorId);
	}

	// ---- likes ----

	public Post like(long userId, String publicId, boolean like) {
		PostRow row = this.posts.visiblePost(publicId, userId).orElseThrow(PostService::postNotFound);
		if (like) {
			this.posts.like(row.id(), userId);
		}
		else {
			this.posts.unlike(row.id(), userId);
		}
		return post(publicId, userId);
	}

	public List<Person> likers(String publicId, Long viewerId) {
		PostRow row = this.posts.visiblePost(publicId, viewerId).orElseThrow(PostService::postNotFound);
		return this.posts.likers(row.id(), viewerId, 200).stream().map(Person::of).toList();
	}

	// ---- comments ----

	public Page<Comment> comments(String publicId, Long viewerId, Long after) {
		PostRow post = this.posts.visiblePost(publicId, viewerId).orElseThrow(PostService::postNotFound);
		List<CommentRow> rows = this.posts.comments(post.id(), viewerId, after, COMMENTS_PAGE);
		List<Comment> comments = rows.stream().map(row -> toComment(row, post, viewerId)).toList();
		Long next = (rows.size() == COMMENTS_PAGE) ? rows.get(rows.size() - 1).id() : null;
		return new Page<>(comments, next);
	}

	public Comment comment(long userId, String publicId, String text) {
		PostRow post = this.posts.visiblePost(publicId, userId).orElseThrow(PostService::postNotFound);
		String clean = clean(text);
		if (clean.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "empty_comment", "Write something first.");
		}
		this.moderation.checkText(clean);
		long id = this.posts.insertComment(post.id(), userId, clean);
		return toComment(this.posts.comment(id).orElseThrow(), post, userId);
	}

	/** Your own comments, and any comment on your posts. */
	public void deleteComment(long userId, long commentId) {
		CommentRow comment = this.posts.comment(commentId).orElseThrow(PostService::commentNotFound);
		PostRow post = this.posts.postById(comment.postId()).orElseThrow(PostService::commentNotFound);
		if (comment.authorId() != userId && post.authorId() != userId) {
			throw commentNotFound();
		}
		this.posts.deleteComment(commentId);
	}

	// ---- profile photo and cover ----

	/**
	 * Uses one of your uploads as your profile photo (also your account photo) or cover; null removes it. The
	 * previous one is deleted.
	 */
	public void setProfilePhoto(long userId, String photoId, boolean cover) {
		String column = cover ? "cover_photo_id" : "avatar_photo_id";
		PhotoRow photo = null;
		if (photoId != null) {
			List<PhotoRow> found = this.posts.unusedPhotos(userId, List.of(photoId));
			if (found.isEmpty()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_photos",
						"That photo is missing or already used. Please add it again.");
			}
			photo = found.get(0);
		}
		PhotoRow chosen = photo;
		Long previous = this.transactions.execute(status -> {
			Long old = this.jdbc.queryForObject("SELECT " + column + " FROM wanderbook_profiles WHERE user_id = ?",
					Long.class, userId);
			this.jdbc.update("UPDATE wanderbook_profiles SET " + column + " = ?, updated_at = now() WHERE user_id = ?",
					(chosen != null) ? chosen.id() : null, userId);
			if (!cover) {
				this.jdbc.update("UPDATE users SET profile_picture_url = ?, updated_at = now() WHERE id = ?",
						(chosen != null) ? this.media.publicUrl(chosen.objectKey()) : null, userId);
			}
			return old;
		});
		this.posts.photoById(previous).ifPresent(old -> {
			this.posts.deletePhoto(old.id());
			this.storage.delete(old.objectKey());
		});
	}

	public String coverUrl(long userId) {
		List<String> keys = this.jdbc.queryForList("""
				SELECT ph.object_key FROM wanderbook_profiles pr JOIN wanderbook_photos ph ON ph.id = pr.cover_photo_id
				WHERE pr.user_id = ?""", String.class, userId);
		return keys.isEmpty() ? null : this.media.publicUrl(keys.get(0));
	}

	// ---- clean-up ----

	/** Hourly: deletes uploads never used within a day (and their files). */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void deleteAbandonedUploads() {
		for (PhotoRow photo : this.posts.abandonedPhotos(Instant.now().minus(Duration.ofDays(1)), 200)) {
			this.posts.deletePhoto(photo.id());
			this.storage.delete(photo.objectKey());
		}
	}

	// ---- helpers ----

	private PostRow own(String publicId, long userId) {
		PostRow row = this.posts.visiblePost(publicId, userId).orElseThrow(PostService::postNotFound);
		if (row.authorId() != userId) {
			throw postNotFound();
		}
		return row;
	}

	private Page<Post> page(List<PostRow> rows, Long viewerId) {
		Long next = (rows.size() == FEED_PAGE) ? rows.get(rows.size() - 1).id() : null;
		return new Page<>(toPosts(rows, viewerId), next);
	}

	private List<Post> toPosts(List<PostRow> rows, Long viewerId) {
		Map<Long, List<PhotoRow>> photos = this.posts.photosOf(rows.stream().map(PostRow::id).toList());
		return rows.stream().map(row -> {
			Person author = new Person(row.authorPublicId(), row.authorName(), row.authorPhotoUrl(), null,
					row.authorPrivate());
			PlaceTag place = (row.placeName() == null) ? null
					: new PlaceTag(row.placeName(), row.placeRegion(), row.placeCountry());
			List<Photo> list = photos.getOrDefault(row.id(), List.of())
				.stream()
				.map(photo -> new Photo(photo, this.media))
				.toList();
			return new Post(row.publicId(), author, row.caption(), place, list, row.likes(), row.comments(),
					row.liked(), viewerId != null && viewerId == row.authorId(), row.createdAt(), row.editedAt());
		}).toList();
	}

	private static Comment toComment(CommentRow row, PostRow post, Long viewerId) {
		Person author = new Person(row.authorPublicId(), row.authorName(), row.authorPhotoUrl(), null, false);
		boolean canDelete = viewerId != null && (viewerId == row.authorId() || viewerId == post.authorId());
		return new Comment(row.id(), author, row.text(), row.createdAt(), canDelete);
	}

	private static String textToCheck(String caption, PlaceTag place) {
		return (place == null) ? caption : caption + "\n" + place.name() + ", " + place.region() + ", " + place.country();
	}

	private static PlaceTag cleanPlace(PlaceTag place) {
		if (place == null || clean(place.name()).isEmpty()) {
			return null;
		}
		return new PlaceTag(limit(clean(place.name()), 200), limit(clean(place.region()), 200),
				limit(clean(place.country()), 100));
	}

	private static String name(PlaceTag tag) {
		return (tag != null) ? tag.name() : null;
	}

	private static String region(PlaceTag tag) {
		return (tag != null) ? tag.region() : null;
	}

	private static String country(PlaceTag tag) {
		return (tag != null) ? tag.country() : null;
	}

	private static String limit(String text, int max) {
		return (text.length() > max) ? text.substring(0, max) : text;
	}

	static String clean(String text) {
		return (text == null) ? "" : text.strip().replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replaceAll("\\n{3,}", "\n\n");
	}

	private static String randomId() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private static ApiException postNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "post_not_found", "This post isn't available.");
	}

	private static ApiException commentNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "comment_not_found", "This comment isn't available.");
	}

}
