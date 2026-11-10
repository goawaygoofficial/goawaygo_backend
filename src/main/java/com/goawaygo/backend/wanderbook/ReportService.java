package com.goawaygo.backend.wanderbook;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.config.WebProperties;
import com.goawaygo.backend.mail.EmailOutbox;
import com.goawaygo.backend.mail.EmailTemplates;
import com.goawaygo.backend.media.MediaProperties;
import com.goawaygo.backend.wanderbook.PostRepository.CommentRow;
import com.goawaygo.backend.wanderbook.PostRepository.PostRow;

/**
 * Reports of posts, comments and people (required by the app stores for apps with user posts). Each new report is
 * emailed to the team; admins ({@code users.is_admin}) review them on the website's admin page and remove the
 * content or dismiss the report.
 */
@Service
public class ReportService {

	static final Set<String> TARGETS = Set.of("post", "comment", "user");

	static final Set<String> REASONS = Set.of("spam", "nudity", "violence", "hate", "harassment", "self_harm",
			"illegal", "false_information", "other");

	private final JdbcTemplate jdbc;

	private final PostRepository posts;

	private final PostService postService;

	private final WanderbookService profiles;

	private final EmailOutbox email;

	private final MediaProperties media;

	private final String teamEmail;

	private final String reviewLink;

	public ReportService(JdbcTemplate jdbc, PostRepository posts, PostService postService, WanderbookService profiles,
			EmailOutbox email, MediaProperties media, WebProperties web,
			@Value("${goawaygo.wanderbook.reports-email}") String teamEmail) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.postService = postService;
		this.profiles = profiles;
		this.email = email;
		this.media = media;
		this.teamEmail = teamEmail;
		this.reviewLink = web.baseUrl().replaceAll("/+$", "") + "/admin/reports";
	}

	/**
	 * Reports a post (its public id), a comment (its id) or a person (their profile's public id). Reporting the same
	 * thing again changes nothing.
	 */
	@Transactional
	public void report(long reporterId, String targetType, String target, String reason, String details) {
		if (!TARGETS.contains(targetType) || !REASONS.contains(reason)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_report", "Choose what you're reporting and why.");
		}
		long targetId;
		long targetUserId;
		switch (targetType) {
			case "post" -> {
				PostRow post = this.posts.visiblePost(target, reporterId).orElseThrow(ReportService::notFound);
				targetId = post.id();
				targetUserId = post.authorId();
			}
			case "comment" -> {
				CommentRow comment = this.posts.comment(parseId(target)).orElseThrow(ReportService::notFound);
				PostRow post = this.posts.postById(comment.postId()).orElseThrow(ReportService::notFound);
				this.posts.visiblePost(post.publicId(), reporterId).orElseThrow(ReportService::notFound);
				targetId = comment.id();
				targetUserId = comment.authorId();
			}
			default -> {
				targetUserId = this.profiles.userIdOf(target, reporterId);
				targetId = targetUserId;
			}
		}
		if (targetUserId == reporterId) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_report_self", "You can't report yourself.");
		}
		String text = UserText.clean(details);
		int added = this.jdbc.update("""
				INSERT INTO wanderbook_reports (reporter_id, target_type, target_id, target_user_id, reason, details)
				VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (reporter_id, target_type, target_id) DO NOTHING""",
				reporterId, targetType, targetId, targetUserId, reason, (text.length() > 500) ? text.substring(0, 500) : text);
		if (added > 0) {
			this.email.queue(EmailTemplates.wanderbookReport(this.teamEmail, targetType, reason, text, this.reviewLink));
		}
	}

	// ---- admin ----

	/** An open report and what it is about, for the admin page. */
	public record Report(long id, String targetType, String reason, String details, Instant createdAt,
			String reporterName, long reportsOnTarget, Map<String, Object> target) {
	}

	public List<Report> openReports(long adminId) {
		requireAdmin(adminId);
		return this.jdbc.query("""
				SELECT r.*, u.name AS reporter_name,
				       (SELECT count(*) FROM wanderbook_reports o WHERE o.status = 'open'
				        AND o.target_type = r.target_type AND o.target_id = r.target_id) AS on_target
				FROM wanderbook_reports r JOIN users u ON u.id = r.reporter_id
				WHERE r.status = 'open' ORDER BY r.id DESC LIMIT 200""",
				(rs, row) -> new Report(rs.getLong("id"), rs.getString("target_type"), rs.getString("reason"),
						rs.getString("details"), rs.getTimestamp("created_at").toInstant(),
						rs.getString("reporter_name"), rs.getLong("on_target"),
						target(rs.getString("target_type"), rs.getLong("target_id"), rs.getLong("target_user_id"))));
	}

	/**
	 * Closes a report and every other open report on the same thing. {@code remove}: deletes the post or comment
	 * first (people are dismissed or handled by hand).
	 */
	@Transactional
	public void resolve(long adminId, long reportId, boolean remove) {
		requireAdmin(adminId);
		Map<String, Object> report = this.jdbc
			.queryForList("SELECT target_type, target_id FROM wanderbook_reports WHERE id = ?", reportId)
			.stream()
			.findFirst()
			.orElseThrow(ReportService::notFound);
		String type = (String) report.get("target_type");
		long targetId = ((Number) report.get("target_id")).longValue();
		if (remove) {
			switch (type) {
				case "post" -> this.postService.deletePost(targetId);
				case "comment" -> this.posts.deleteComment(targetId);
				default -> throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_remove_user",
						"People can't be removed here; dismiss the report or handle the account by hand.");
			}
		}
		this.jdbc.update("""
				UPDATE wanderbook_reports SET status = 'resolved', resolved_at = now(), resolution = ?
				WHERE status = 'open' AND target_type = ? AND target_id = ?""", remove ? "removed" : "dismissed", type,
				targetId);
	}

	private Map<String, Object> target(String type, long targetId, long userId) {
		return switch (type) {
			case "post" -> this.posts.postById(targetId).map(post -> Map.<String, Object>of("exists", true,
					"publicId", post.publicId(), "author", post.authorName(), "caption", post.caption(), "photos",
					this.posts.photosOfPost(post.id()).stream().map(p -> this.media.publicUrl(p.objectKey())).toList()))
				.orElse(Map.of("exists", false));
			case "comment" -> this.posts.comment(targetId)
				.map(comment -> Map.<String, Object>of("exists", true, "author", comment.authorName(), "text",
						comment.text()))
				.orElse(Map.of("exists", false));
			default -> this.jdbc.queryForList("""
					SELECT pr.public_id, u.name, pr.bio FROM wanderbook_profiles pr JOIN users u ON u.id = pr.user_id
					WHERE pr.user_id = ?""", userId)
				.stream()
				.findFirst()
				.map(row -> Map.<String, Object>of("exists", true, "publicId", row.get("public_id"), "author",
						row.get("name"), "bio", row.get("bio")))
				.orElse(Map.of("exists", false));
		};
	}

	private void requireAdmin(long userId) {
		Boolean admin = this.jdbc.queryForObject("SELECT is_admin FROM users WHERE id = ?", Boolean.class, userId);
		if (!Boolean.TRUE.equals(admin)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "not_admin", "Only GoAwayGo admins can do this.");
		}
	}

	private static long parseId(String text) {
		try {
			return Long.parseLong(text);
		}
		catch (NumberFormatException ex) {
			throw notFound();
		}
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "not_found", "That isn't available.");
	}

}
