package com.goawaygo.backend.discover;

import java.time.Instant;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.plans.PlanViews;

/**
 * Recently viewed (Me, 4.6): the places and trip plans a signed-in user opened, newest first. Signed-out users keep
 * theirs on the device.
 */
@RestController
@RequestMapping("/api/me/recently-viewed")
public class RecentlyViewedController {

	private final SearchHistory places;

	private final PlanViews plans;

	public RecentlyViewedController(SearchHistory places, PlanViews plans) {
		this.places = places;
		this.plans = plans;
	}

	public record ViewedPlace(Place place, Instant viewedAt) {
	}

	public record RecentlyViewed(List<ViewedPlace> places, List<PlanViews.Viewed> plans) {
	}

	@GetMapping
	public RecentlyViewed list(@AuthenticationPrincipal Jwt jwt) {
		long userId = Long.parseLong(jwt.getSubject());
		List<ViewedPlace> viewed = this.places.latest(userId, SearchHistory.KEEP)
			.stream()
			.map(e -> new ViewedPlace(e.place(), e.searchedAt()))
			.toList();
		return new RecentlyViewed(viewed, this.plans.latest(userId));
	}

	/** Clears both lists (the home banner's recent searches too). */
	@DeleteMapping
	@Transactional
	public ResponseEntity<Void> clear(@AuthenticationPrincipal Jwt jwt) {
		long userId = Long.parseLong(jwt.getSubject());
		this.places.clear(userId);
		this.plans.clear(userId);
		return ResponseEntity.noContent().build();
	}

}
