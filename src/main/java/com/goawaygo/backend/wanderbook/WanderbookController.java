package com.goawaygo.backend.wanderbook;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.ratelimit.RateLimiter;
import com.goawaygo.backend.wanderbook.WanderbookService.Page;
import com.goawaygo.backend.wanderbook.WanderbookService.Person;
import com.goawaygo.backend.wanderbook.WanderbookService.Profile;

/**
 * Wanderbook profiles, follows, follow requests, blocks and people search. Viewing is public (rate limited per IP);
 * everything that changes something needs sign-in. Profiles are addressed by their random public id.
 */
@RestController
@RequestMapping("/api/wanderbook")
public class WanderbookController {

	private static final String PUBLIC_ID = "{publicId:[0-9a-f]{12}}";

	private final WanderbookService wanderbook;

	private final RateLimiter rateLimiter;

	private final WanderbookProperties.Limits limits;

	public WanderbookController(WanderbookService wanderbook, RateLimiter rateLimiter,
			WanderbookProperties properties) {
		this.wanderbook = wanderbook;
		this.rateLimiter = rateLimiter;
		this.limits = properties.limits();
	}

	// ---- my profile ----

	@GetMapping("/me")
	public Profile me(@AuthenticationPrincipal Jwt jwt) {
		return this.wanderbook.me(userId(jwt));
	}

	public record ProfileUpdate(@Size(max = 150) String bio, @Size(max = 100) String homeTown,
			@NotNull Boolean isPrivate) {
	}

	@PutMapping("/me")
	public Profile update(@Valid @RequestBody ProfileUpdate body, @AuthenticationPrincipal Jwt jwt) {
		long userId = change(jwt);
		return this.wanderbook.update(userId, body.bio(), body.homeTown(), body.isPrivate());
	}

	@GetMapping("/me/follow-requests")
	public Page<Person> followRequests(@RequestParam(name = "before", required = false) Long before,
			@AuthenticationPrincipal Jwt jwt) {
		return this.wanderbook.followRequests(userId(jwt), before);
	}

	@PostMapping("/me/follow-requests/" + PUBLIC_ID)
	public ResponseEntity<Void> approve(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		this.wanderbook.approve(change(jwt), publicId);
		return ResponseEntity.noContent().build();
	}

	/** Declines a follow request. */
	@DeleteMapping("/me/follow-requests/" + PUBLIC_ID)
	public ResponseEntity<Void> decline(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		this.wanderbook.removeFollower(change(jwt), publicId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/me/followers/" + PUBLIC_ID)
	public ResponseEntity<Void> removeFollower(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		this.wanderbook.removeFollower(change(jwt), publicId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me/blocked")
	public List<Person> blocked(@AuthenticationPrincipal Jwt jwt) {
		return this.wanderbook.blocked(userId(jwt));
	}

	// ---- other profiles (public) ----

	@GetMapping("/profiles/" + PUBLIC_ID)
	public Profile profile(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		view(request);
		return this.wanderbook.profile(publicId, optionalUserId(jwt));
	}

	@GetMapping("/profiles/" + PUBLIC_ID + "/followers")
	public Page<Person> followers(@PathVariable String publicId,
			@RequestParam(name = "before", required = false) Long before, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		view(request);
		return this.wanderbook.follows(publicId, true, optionalUserId(jwt), before);
	}

	@GetMapping("/profiles/" + PUBLIC_ID + "/following")
	public Page<Person> following(@PathVariable String publicId,
			@RequestParam(name = "before", required = false) Long before, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		view(request);
		return this.wanderbook.follows(publicId, false, optionalUserId(jwt), before);
	}

	/** Follows (public profile) or asks to follow (private profile). */
	@PostMapping("/profiles/" + PUBLIC_ID + "/follow")
	public Profile follow(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		return this.wanderbook.follow(publicId, change(jwt));
	}

	/** Unfollows, or cancels a follow request. */
	@DeleteMapping("/profiles/" + PUBLIC_ID + "/follow")
	public Profile unfollow(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		return this.wanderbook.unfollow(publicId, change(jwt));
	}

	@PostMapping("/profiles/" + PUBLIC_ID + "/block")
	public Profile block(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		return this.wanderbook.block(publicId, change(jwt));
	}

	@DeleteMapping("/profiles/" + PUBLIC_ID + "/block")
	public ResponseEntity<Void> unblock(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt) {
		this.wanderbook.unblock(publicId, change(jwt));
		return ResponseEntity.noContent().build();
	}

	/** People whose name contains {@code q} (2 to 50 characters). */
	@GetMapping("/people")
	public List<Person> search(@RequestParam("q") String text, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		String query = text.trim();
		if (query.length() < 2 || query.length() > 50) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_query", "Type 2 to 50 characters.");
		}
		this.rateLimiter.consume("wanderbook-search-ip", request.getRemoteAddr(), this.limits.searchPerIp());
		return this.wanderbook.search(query, optionalUserId(jwt));
	}

	// ---- helpers ----

	private void view(HttpServletRequest request) {
		this.rateLimiter.consume("wanderbook-view-ip", request.getRemoteAddr(), this.limits.viewPerIp());
	}

	/** The signed-in user, counted against the per-user change limit. */
	private long change(Jwt jwt) {
		long userId = userId(jwt);
		this.rateLimiter.consume("wanderbook-change-user", String.valueOf(userId), this.limits.changesPerUser());
		return userId;
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	private static Long optionalUserId(Jwt jwt) {
		return (jwt != null) ? Long.valueOf(jwt.getSubject()) : null;
	}

}
