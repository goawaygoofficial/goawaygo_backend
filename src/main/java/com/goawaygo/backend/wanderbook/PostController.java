package com.goawaygo.backend.wanderbook;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.ratelimit.RateLimiter;
import com.goawaygo.backend.wanderbook.PostService.Comment;
import com.goawaygo.backend.wanderbook.PostService.PlaceTag;
import com.goawaygo.backend.wanderbook.PostService.Post;
import com.goawaygo.backend.wanderbook.PostService.Uploaded;
import com.goawaygo.backend.wanderbook.WanderbookService.Page;
import com.goawaygo.backend.wanderbook.WanderbookService.Person;

/**
 * Wanderbook photos, posts, the feed, likes, comments, reports and the admins' review. Reading is public (rate
 * limited per IP, like profiles); everything else needs sign-in and is limited per user.
 */
@RestController
public class PostController {

	private static final String POST = "/api/wanderbook/posts/{publicId:[0-9a-f]{12}}";

	private final PostService posts;

	private final WanderbookService profiles;

	private final ReportService reports;

	private final RateLimiter rateLimiter;

	private final WanderbookProperties.Limits limits;

	public PostController(PostService posts, WanderbookService profiles, ReportService reports,
			RateLimiter rateLimiter, WanderbookProperties properties) {
		this.posts = posts;
		this.profiles = profiles;
		this.reports = reports;
		this.rateLimiter = rateLimiter;
		this.limits = properties.limits();
	}

	// ---- photos ----

	/** Uploads one photo (multipart field {@code file}); use its id in a post, or as profile photo or cover. */
	@PostMapping("/api/wanderbook/photos")
	public Uploaded upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt)
			throws IOException {
		long userId = userId(jwt);
		this.rateLimiter.consume("wanderbook-upload-user", String.valueOf(userId), this.limits.uploadsPerUser());
		return this.posts.upload(userId, file.getBytes());
	}

	public record ProfilePhoto(@Size(max = 32) String photoId) {
	}

	/** Sets (or with {@code photoId: null} removes) the profile photo, which is also the account photo. */
	@PutMapping("/api/wanderbook/me/avatar")
	public WanderbookService.Profile avatar(@RequestBody ProfilePhoto body, @AuthenticationPrincipal Jwt jwt) {
		long userId = content(jwt);
		this.posts.setProfilePhoto(userId, body.photoId(), false);
		return this.profiles.me(userId);
	}

	@PutMapping("/api/wanderbook/me/cover")
	public WanderbookService.Profile cover(@RequestBody ProfilePhoto body, @AuthenticationPrincipal Jwt jwt) {
		long userId = content(jwt);
		this.posts.setProfilePhoto(userId, body.photoId(), true);
		return this.profiles.me(userId);
	}

	// ---- posts ----

	public record PlaceBody(@Size(max = 200) String name, @Size(max = 200) String region,
			@Size(max = 100) String country) {

		PlaceTag tag() {
			return new PlaceTag(this.name, this.region, this.country);
		}

	}

	public record NewPost(@NotNull @Size(min = 1, max = PostService.MAX_PHOTOS) List<@NotBlank String> photoIds,
			@Size(max = 2200) String caption, @Valid PlaceBody place) {
	}

	@PostMapping("/api/wanderbook/posts")
	public ResponseEntity<Post> create(@Valid @RequestBody NewPost body, @AuthenticationPrincipal Jwt jwt) {
		long userId = content(jwt);
		Post post = this.posts.create(userId, body.photoIds(), body.caption(),
				(body.place() != null) ? body.place().tag() : null);
		return ResponseEntity.status(HttpStatus.CREATED).body(post);
	}

	@GetMapping(POST)
	public Post post(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
		view(request);
		return this.posts.post(publicId, optionalUserId(jwt));
	}

	public record PostEdit(@Size(max = 2200) String caption, @Valid PlaceBody place) {
	}

	@PutMapping(POST)
	public Post edit(@PathVariable String publicId, @Valid @RequestBody PostEdit body,
			@AuthenticationPrincipal Jwt jwt) {
		return this.posts.edit(content(jwt), publicId, body.caption(),
				(body.place() != null) ? body.place().tag() : null);
	}

	@DeleteMapping(POST)
	public ResponseEntity<Void> delete(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		this.posts.delete(userId(jwt), publicId);
		return ResponseEntity.noContent().build();
	}

	/** Everyone's newest posts the viewer may see (decided for 4.2). */
	@GetMapping("/api/wanderbook/feed")
	public Page<Post> feed(@RequestParam(name = "before", required = false) Long before,
			@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
		view(request);
		return this.posts.feed(optionalUserId(jwt), before);
	}

	@GetMapping("/api/wanderbook/profiles/{publicId:[0-9a-f]{12}}/posts")
	public Page<Post> profilePosts(@PathVariable String publicId,
			@RequestParam(name = "before", required = false) Long before, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		view(request);
		return this.profiles.posts(publicId, optionalUserId(jwt), before);
	}

	// ---- likes and comments ----

	@PostMapping(POST + "/like")
	public Post like(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		return this.posts.like(content(jwt), publicId, true);
	}

	@DeleteMapping(POST + "/like")
	public Post unlike(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		return this.posts.like(content(jwt), publicId, false);
	}

	@GetMapping(POST + "/likes")
	public List<Person> likes(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		view(request);
		return this.posts.likers(publicId, optionalUserId(jwt));
	}

	@GetMapping(POST + "/comments")
	public Page<Comment> comments(@PathVariable String publicId,
			@RequestParam(name = "after", required = false) Long after, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		view(request);
		return this.posts.comments(publicId, optionalUserId(jwt), after);
	}

	public record NewComment(@NotBlank @Size(max = 500) String text) {
	}

	@PostMapping(POST + "/comments")
	public ResponseEntity<Comment> comment(@PathVariable String publicId, @Valid @RequestBody NewComment body,
			@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.posts.comment(content(jwt), publicId, body.text()));
	}

	@DeleteMapping("/api/wanderbook/comments/{id}")
	public ResponseEntity<Void> deleteComment(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
		this.posts.deleteComment(userId(jwt), id);
		return ResponseEntity.noContent().build();
	}

	// ---- reports and review ----

	/**
	 * @param targetType {@code post}, {@code comment} or {@code user}
	 * @param target the post's or profile's public id, or the comment's id
	 * @param reason one of {@link ReportService#REASONS}
	 */
	public record NewReport(@NotBlank String targetType, @NotBlank @Size(max = 32) String target,
			@NotBlank String reason, @Size(max = 500) String details) {
	}

	@PostMapping("/api/wanderbook/reports")
	public ResponseEntity<Void> report(@Valid @RequestBody NewReport body, @AuthenticationPrincipal Jwt jwt) {
		long userId = userId(jwt);
		this.rateLimiter.consume("wanderbook-report-user", String.valueOf(userId), this.limits.reportsPerUser());
		this.reports.report(userId, body.targetType(), body.target(), body.reason(), body.details());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/admin/wanderbook/reports")
	public List<ReportService.Report> openReports(@AuthenticationPrincipal Jwt jwt) {
		return this.reports.openReports(userId(jwt));
	}

	/** @param action {@code remove} (delete the post or comment) or {@code dismiss} */
	public record Resolution(@NotBlank String action) {
	}

	@PostMapping("/api/admin/wanderbook/reports/{id}/resolve")
	public ResponseEntity<Void> resolve(@PathVariable long id, @Valid @RequestBody Resolution body,
			@AuthenticationPrincipal Jwt jwt) {
		if (!body.action().equals("remove") && !body.action().equals("dismiss")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_action", "Choose remove or dismiss.");
		}
		this.reports.resolve(userId(jwt), id, body.action().equals("remove"));
		return ResponseEntity.noContent().build();
	}

	// ---- helpers ----

	private void view(HttpServletRequest request) {
		this.rateLimiter.consume("wanderbook-view-ip", request.getRemoteAddr(), this.limits.viewPerIp());
	}

	/** The signed-in user, counted against the per-user limit for posts, comments, likes and profile photos. */
	private long content(Jwt jwt) {
		long userId = userId(jwt);
		this.rateLimiter.consume("wanderbook-content-user", String.valueOf(userId), this.limits.contentPerUser());
		return userId;
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	private static Long optionalUserId(Jwt jwt) {
		return (jwt != null) ? Long.valueOf(jwt.getSubject()) : null;
	}

}
