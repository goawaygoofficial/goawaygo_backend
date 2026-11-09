package com.goawaygo.backend.discover;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.ratelimit.RateLimiter;

/**
 * Discover: place search, destination pages, the home banner's photos and ideas. Everything except the signed-in
 * user's recent searches is public. Each client IP is rate limited (destination pages cost an AI request each).
 */
@RestController
@RequestMapping("/api/discover")
public class DiscoverController {

	private static final int MAX_PHOTO_REQUESTS = SearchHistory.BANNER;

	private final PlaceSearch places;

	private final Destinations destinations;

	private final SpotPhotos photos;

	private final SearchHistory history;

	private final Ideas ideas;

	private final RateLimiter rateLimiter;

	private final DiscoverProperties.Limits limits;

	private final Executor executor;

	public DiscoverController(PlaceSearch places, Destinations destinations, SpotPhotos photos, SearchHistory history,
			Ideas ideas, RateLimiter rateLimiter, DiscoverProperties properties,
			@Qualifier("discoverExecutor") Executor executor) {
		this.places = places;
		this.destinations = destinations;
		this.photos = photos;
		this.history = history;
		this.ideas = ideas;
		this.rateLimiter = rateLimiter;
		this.limits = properties.limits();
		this.executor = executor;
	}

	/** Places matching what the user is typing (at least 2 characters). */
	@GetMapping("/places")
	public List<Place> suggest(@RequestParam("q") String text, HttpServletRequest request) {
		String query = text.trim();
		if (query.length() < 2 || query.length() > 100) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_query", "Type 2 to 100 characters.");
		}
		this.rateLimiter.consume("discover-search-ip", request.getRemoteAddr(), this.limits.searchPerIp());
		return this.places.suggest(query);
	}

	/** A destination page: the picked place (or the best match for the text), its 9 cards and spot photos. */
	@GetMapping("/destination")
	public Destinations.Page destination(@RequestParam(name = "placeId", required = false) String placeId,
			@RequestParam(name = "q", required = false) String text, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest request) {
		boolean hasId = placeId != null && !placeId.isBlank() && placeId.length() <= 255;
		boolean hasText = text != null && text.trim().length() >= 2 && text.trim().length() <= 100;
		if (!hasId && !hasText) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_query", "Pick a place or type 2 to 100 characters.");
		}
		this.rateLimiter.consume("discover-destination-ip", request.getRemoteAddr(), this.limits.destinationPerIp());
		Long userId = (jwt != null) ? Long.valueOf(jwt.getSubject()) : null;
		return this.destinations.page(hasId ? placeId : null, hasText ? text.trim() : null, userId);
	}

	/** One searched place on the home banner: the place, the spot shown and its photo (null if none found). */
	public record BannerSlide(Place place, String spot, SpotPhotos.Photo photo) {
	}

	/** The signed-in user's last 5 searched places, newest first, each with a live tourist-spot photo. */
	@GetMapping("/recent")
	public List<BannerSlide> recent(@AuthenticationPrincipal Jwt jwt) {
		List<SearchHistory.Entry> entries = this.history.latest(Long.parseLong(jwt.getSubject()), SearchHistory.BANNER);
		List<PhotoRequest> requests = entries.stream()
			.map(e -> new PhotoRequest((e.topSpot() != null) ? e.topSpot() : e.place().name(), e.place().name(),
					e.place().type(), e.place().country(), e.place().lat(), e.place().lon()))
			.toList();
		return slides(requests, entries.stream().map(SearchHistory.Entry::place).toList());
	}

	/** A spot to find a photo for (the banner of a signed-out user, whose history stays on the device). */
	public record PhotoRequest(@NotBlank @Size(max = 200) String spot, @NotBlank @Size(max = 200) String place,
			@NotBlank String type, @NotBlank @Size(max = 100) String country,
			@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
			@NotNull @DecimalMin("-180") @DecimalMax("180") Double lon) {
	}

	public record PhotoRequests(@NotNull @Size(max = MAX_PHOTO_REQUESTS) List<@Valid PhotoRequest> items) {
	}

	/** Live photos for up to 5 searched places (signed-out users' recent searches, kept on their device). */
	@PostMapping("/photos")
	public List<BannerSlide> photos(@Valid @RequestBody PhotoRequests body, HttpServletRequest request) {
		this.rateLimiter.consume("discover-photos-ip", request.getRemoteAddr(), this.limits.photosPerIp());
		List<Place> placesShown = body.items().stream().map(r -> new Place("", r.place(), type(r.type()), "", "",
				r.country(), "", r.lat(), r.lon(), r.place() + ", " + r.country())).toList();
		return slides(body.items(), placesShown);
	}

	/** {@code count} (1-12) random ideas for the next trip; the app asks for a new set every minute. */
	@GetMapping("/ideas")
	public List<Ideas.Idea> ideas(@RequestParam(name = "count", defaultValue = "6") int count,
			HttpServletRequest request) {
		this.rateLimiter.consume("discover-ideas-ip", request.getRemoteAddr(), this.limits.ideasPerIp());
		return this.ideas.random(Math.max(1, Math.min(12, count)));
	}

	private List<BannerSlide> slides(List<PhotoRequest> requests, List<Place> placesShown) {
		List<CompletableFuture<SpotPhotos.Spot>> lookups = requests.stream()
			.map(r -> CompletableFuture.supplyAsync(() -> this.photos.photoOf(r.spot(), new Place("", r.place(),
					type(r.type()), "", "", r.country(), "", r.lat(), r.lon(), r.place())), this.executor))
			.toList();
		return IntStream.range(0, requests.size()).mapToObj(i -> {
			SpotPhotos.Spot spot = lookups.get(i).join();
			return new BannerSlide(placesShown.get(i), (spot != null) ? spot.name() : requests.get(i).spot(),
					(spot != null) ? spot.photo() : null);
		}).toList();
	}

	private static String type(String type) {
		return List.of("country", "region", "city", "place").contains(type) ? type : "place";
	}

}
