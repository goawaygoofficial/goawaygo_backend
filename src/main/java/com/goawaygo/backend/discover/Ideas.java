package com.goawaygo.backend.discover;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * "Ideas for your next trip": varied real places from all over the world (beaches, mountains, heritage, cities,
 * hidden gems and crowd favourites). Once per {@code refresh-after} the AI suggests a fresh list; each place is kept
 * only if Wikipedia confirms it (same name, a location and a photo). The list lives in memory only, never in the
 * database; every request gets a random handful from it.
 */
@Component
public class Ideas {

	private static final Logger log = LoggerFactory.getLogger(Ideas.class);

	private static final List<String> CONTINENTS = List.of("Africa", "Asia", "Europe", "North America",
			"South America", "Oceania");

	private static final double TEMPERATURE = 0.9;

	private static final int MAX_OUTPUT_TOKENS = 8_000;

	private final AiClient ai;

	private final SpotPhotos photos;

	private final ObjectMapper json;

	private final DiscoverProperties properties;

	private final Executor executor;

	private final Clock clock;

	private final String systemPrompt = DestinationCards.prompt("prompts/ideas-system.txt");

	private final String requestPrompt = DestinationCards.prompt("prompts/ideas-request.txt");

	private final ReentrantLock refreshing = new ReentrantLock();

	private volatile Pool pool;

	public Ideas(AiClient ai, SpotPhotos photos, ObjectMapper json, DiscoverProperties properties,
			@Qualifier("discoverExecutor") Executor executor, Clock clock) {
		this.ai = ai;
		this.photos = photos;
		this.json = json;
		this.properties = properties;
		this.executor = executor;
		this.clock = clock;
	}

	/**
	 * @param id stable for the same place, so the app can tell whether it is already saved
	 * @param category beach, mountain, lake, waterfall, island, nature, heritage, old town, city, landmark, desert,
	 *     temple
	 * @param kind {@code popular} or {@code hidden gem}
	 */
	public record Idea(String id, String name, String area, String country, String category, String kind,
			String summary, double lat, double lon, SpotPhotos.Photo photo) {
	}

	private record Pool(List<Idea> ideas, Instant madeAt) {
	}

	/** {@code count} random ideas (empty if none could be made right now). */
	public List<Idea> random(int count) {
		Pool current = currentPool();
		if (current == null || current.ideas().isEmpty()) {
			return List.of();
		}
		List<Idea> shuffled = new ArrayList<>(current.ideas());
		Collections.shuffle(shuffled, new Random(ThreadLocalRandom.current().nextLong()));
		return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
	}

	private Pool currentPool() {
		Pool current = this.pool;
		boolean fresh = current != null
				&& current.madeAt().plus(this.properties.ideas().refreshAfter()).isAfter(this.clock.instant());
		if (fresh) {
			return current;
		}
		if (current != null) {
			// Someone else may already be making the next list: keep serving the old one meanwhile.
			if (this.refreshing.tryLock()) {
				try {
					this.pool = make(current);
				}
				finally {
					this.refreshing.unlock();
				}
			}
			return this.pool;
		}
		this.refreshing.lock();
		try {
			if (this.pool == null) {
				Pool made = make(null);
				this.pool = made.ideas().isEmpty() ? null : made;
				return made;
			}
			return this.pool;
		}
		finally {
			this.refreshing.unlock();
		}
	}

	private Pool make(Pool previous) {
		List<Suggestion> suggestions = suggest(previous);
		List<CompletableFuture<Idea>> checks = suggestions.stream()
			.map(s -> CompletableFuture.supplyAsync(() -> verify(s), this.executor))
			.toList();
		List<Idea> verified = new ArrayList<>();
		for (CompletableFuture<Idea> check : checks) {
			Idea idea = check.join();
			if (idea != null && verified.stream().noneMatch(i -> i.id().equals(idea.id()))) {
				verified.add(idea);
			}
		}
		log.info("Ideas: {} of {} suggested places confirmed on Wikipedia", verified.size(), suggestions.size());
		if (verified.isEmpty()) {
			return (previous != null) ? new Pool(previous.ideas(), this.clock.instant()) : new Pool(List.of(),
					this.clock.instant());
		}
		List<SpotPhotos.Photo> credited = this.photos.withCredits(verified.stream().map(Idea::photo).toList());
		List<Idea> ideas = new ArrayList<>();
		for (int i = 0; i < verified.size(); i++) {
			Idea idea = verified.get(i);
			ideas.add(new Idea(idea.id(), idea.name(), idea.area(), idea.country(), idea.category(), idea.kind(),
					idea.summary(), idea.lat(), idea.lon(), credited.get(i)));
		}
		return new Pool(List.copyOf(ideas), this.clock.instant());
	}

	private List<Suggestion> suggest(Pool previous) {
		String avoid = (previous == null) ? "none"
				: String.join(", ", previous.ideas().stream().map(Idea::name).limit(40).toList());
		String request = this.requestPrompt.replace("{count}", String.valueOf(this.properties.ideas().poolSize()))
			.replace("{startContinent}", CONTINENTS.get(ThreadLocalRandom.current().nextInt(CONTINENTS.size())))
			.replace("{avoid}", avoid);
		AiClient.Answer answer = this.ai.generateJson(this.properties.ideaModels(), this.systemPrompt, request,
				schema(), TEMPERATURE, MAX_OUTPUT_TOKENS);
		if (answer == null) {
			return List.of();
		}
		try {
			RawIdeas raw = this.json.readValue(answer.json(), RawIdeas.class);
			return (raw.ideas() != null) ? raw.ideas() : List.of();
		}
		catch (RuntimeException ex) {
			log.warn("Ideas answer from {} not usable: {}", answer.model(), ex.toString());
			return List.of();
		}
	}

	private Idea verify(Suggestion s) {
		if (s == null || blank(s.name()) || blank(s.country())) {
			return null;
		}
		SpotPhotos.Located found = this.photos.locate(s.name().trim(), (nonNull(s.area()) + " " + s.country()).trim());
		if (found == null) {
			return null;
		}
		String id = (s.name().trim() + "|" + s.country().trim()).toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
		return new Idea(id, s.name().trim(), nonNull(s.area()).trim(), s.country().trim(),
				nonNull(s.category()).trim().toLowerCase(Locale.ROOT),
				"hidden gem".equalsIgnoreCase(nonNull(s.kind()).trim()) ? "hidden gem" : "popular",
				nonNull(s.summary()).trim(), found.lat(), found.lon(), found.photo());
	}

	static Map<String, Object> schema() {
		Map<String, Object> idea = Map.of("type", "OBJECT", "properties",
				Map.of("name", Map.of("type", "STRING"), "area", Map.of("type", "STRING"), "country",
						Map.of("type", "STRING"), "category", Map.of("type", "STRING"), "kind", Map.of("type", "STRING"),
						"summary", Map.of("type", "STRING")),
				"required", List.of("name", "area", "country", "category", "kind", "summary"));
		return Map.of("type", "OBJECT", "properties", Map.of("ideas", Map.of("type", "ARRAY", "items", idea)),
				"required", List.of("ideas"));
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String nonNull(String value) {
		return (value != null) ? value : "";
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawIdeas(List<Suggestion> ideas) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Suggestion(String name, String area, String country, String category, String kind, String summary) {
	}

}
