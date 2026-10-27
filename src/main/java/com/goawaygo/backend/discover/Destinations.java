package com.goawaygo.backend.discover;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.goawaygo.backend.common.error.ApiException;

/**
 * Builds a destination page: the place, its 9 AI cards and a banner of tourist-spot photos. The AI cards and the
 * Wikidata spot lookup run at the same time; the photos follow once the AI has named the top attractions. The page is
 * returned only when everything is ready (the app shows its travel animation meanwhile).
 */
@Service
public class Destinations {

	private static final Logger log = LoggerFactory.getLogger(Destinations.class);

	private final PlaceSearch places;

	private final DestinationCards cards;

	private final SpotPhotos photos;

	private final SearchHistory history;

	private final Executor executor;

	public Destinations(PlaceSearch places, DestinationCards cards, SpotPhotos photos, SearchHistory history,
			@Qualifier("discoverExecutor") Executor executor) {
		this.places = places;
		this.cards = cards;
		this.photos = photos;
		this.history = history;
		this.executor = executor;
	}

	/**
	 * @param status {@code ok}; {@code busy} (the AI couldn't answer: the photos still show); {@code unknown} (the
	 *     AI doesn't know this place)
	 * @param model the AI model that wrote the cards (for support; null unless ok)
	 */
	public record Page(Place place, String status, String model, List<DestinationCards.Card> cards,
			List<SpotPhotos.Spot> spots) {
	}

	/**
	 * @param placeId a place picked from the suggestions (preferred), or null
	 * @param text what the user typed, used when no suggestion was picked
	 * @param userId the signed-in user, or null: their search is added to their recent searches
	 */
	public Page page(String placeId, String text, Long userId) {
		Place place = (placeId != null && !placeId.isBlank()) ? this.places.byId(placeId)
				: this.places.bestMatch(text);
		if (place == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "place_not_found", "We couldn't find that place.");
		}
		long started = System.nanoTime();
		CompletableFuture<DestinationCards.Result> aiCards = CompletableFuture
			.supplyAsync(() -> this.cards.forPlace(place), this.executor);
		CompletableFuture<List<String>> wikidataSpots = CompletableFuture
			.supplyAsync(() -> this.photos.wikidataSpots(place), this.executor);
		DestinationCards.Result result = aiCards.join();
		if ("unknown".equals(result.status())) {
			return new Page(place, "unknown", null, List.of(), List.of());
		}
		List<SpotPhotos.Spot> spots = this.photos.forDestination(place, result.attractionNames(), wikidataSpots.join());
		log.info("Destination {} ready in {} ms (cards: {} by {}, {} photos)", place.label(),
				(System.nanoTime() - started) / 1_000_000, result.status(), result.model(), spots.size());
		if (userId != null) {
			this.history.record(userId, place, spots.isEmpty() ? null : spots.get(0).name());
		}
		return new Page(place, result.status(), result.model(), result.cards(), spots);
	}

}
