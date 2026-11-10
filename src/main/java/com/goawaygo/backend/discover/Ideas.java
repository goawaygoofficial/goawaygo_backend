package com.goawaygo.backend.discover;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.goawaygo.backend.common.i18n.Lang;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import java.sql.Timestamp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "Ideas for your next trip": varied real places from all over the world (beaches, mountains, heritage, cities,
 * hidden gems and crowd favourites). The AI suggests a list; each place is kept only if Wikipedia confirms it (same
 * name, a location and a photo). Every request gets a random handful from it.
 * <p>
 * Speed (4.7): the list and its translations are kept in the database ({@code idea_pools}) and in memory, and the
 * daily job makes the next one ({@link #refresh}), so nobody waits for the AI on Home. A request only asks the AI
 * when there is no list at all yet (a new database), or no translation into its language.
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

	private final JdbcTemplate jdbc;

	/** The saved lists are read from the database once, on the first request. */
	private volatile boolean loaded;

	private final String systemPrompt = DestinationCards.prompt("prompts/ideas-system.txt");

	private final String requestPrompt = DestinationCards.prompt("prompts/ideas-request.txt");

	private final ReentrantLock refreshing = new ReentrantLock();

	private volatile Pool pool;

	/** Each language's copy of the current pool (area, country and summary translated; names stay as on Wikipedia). */
	private final Map<Lang, Pool> translated = new ConcurrentHashMap<>();

	private final Map<Lang, ReentrantLock> translating = new ConcurrentHashMap<>();

	public Ideas(AiClient ai, SpotPhotos photos, ObjectMapper json, DiscoverProperties properties,
			@Qualifier("discoverExecutor") Executor executor, Clock clock, JdbcTemplate jdbc) {
		this.ai = ai;
		this.photos = photos;
		this.json = json;
		this.properties = properties;
		this.executor = executor;
		this.clock = clock;
		this.jdbc = jdbc;
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

	/** {@code count} random ideas (empty if none could be made right now), in {@code lang} when translated. */
	public List<Idea> random(int count, Lang lang) {
		Pool current = currentPool();
		if (current == null || current.ideas().isEmpty()) {
			return List.of();
		}
		if (!lang.isEnglish()) {
			current = translatedPool(current, lang);
		}
		List<Idea> shuffled = new ArrayList<>(current.ideas());
		Collections.shuffle(shuffled, new Random(ThreadLocalRandom.current().nextLong()));
		return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
	}

	/** The current list: from memory, else the database, else (a new database) made now. */
	private Pool currentPool() {
		load();
		Pool current = this.pool;
		if (current != null) {
			return current;
		}
		this.refreshing.lock();
		try {
			if (this.pool == null) {
				Pool made = make(null);
				if (!made.ideas().isEmpty()) {
					this.pool = made;
					save(Lang.EN, made);
				}
				return made;
			}
			return this.pool;
		}
		finally {
			this.refreshing.unlock();
		}
	}

	/**
	 * The daily job: a new list once the current one is {@code refresh-after} old, then its translations into every
	 * language. Each is saved as soon as it is made, so a run cut short is finished by the next one; a run with
	 * nothing due does nothing (no AI request).
	 * @return how many lists were made or translated
	 */
	public int refresh() {
		load();
		this.refreshing.lock();
		try {
			int done = 0;
			Pool current = this.pool;
			boolean fresh = current != null
					&& current.madeAt().plus(this.properties.ideas().refreshAfter()).isAfter(this.clock.instant());
			if (!fresh) {
				Pool made = make(current);
				if (!made.ideas().isEmpty() && (current == null || !made.ideas().equals(current.ideas()))) {
					save(Lang.EN, made);
					this.pool = made;
					current = made;
					done++;
				}
			}
			if (current == null) {
				return done;
			}
			Pool english = current;
			List<Lang> missing = new ArrayList<>();
			for (Lang lang : Lang.values()) {
				Pool t = this.translated.get(lang);
				if (!lang.isEnglish() && (t == null || !t.madeAt().equals(english.madeAt()))) {
					missing.add(lang);
				}
			}
			// The languages side by side: one AI request each.
			List<CompletableFuture<List<Idea>>> translations = missing.stream()
				.map(lang -> CompletableFuture.supplyAsync(() -> translate(english.ideas(), lang), this.executor))
				.toList();
			for (int i = 0; i < missing.size(); i++) {
				List<Idea> ideas = translations.get(i).join();
				if (ideas != english.ideas()) {
					Pool made = new Pool(ideas, english.madeAt());
					this.translated.put(missing.get(i), made);
					save(missing.get(i), made);
					done++;
				}
			}
			return done;
		}
		finally {
			this.refreshing.unlock();
		}
	}

	private void load() {
		if (this.loaded) {
			return;
		}
		synchronized (this) {
			if (this.loaded) {
				return;
			}
			this.jdbc.query("SELECT language, made_at, ideas FROM idea_pools", rs -> {
				Lang lang = Lang.of(rs.getString("language"));
				if (lang == null) {
					return;
				}
				try {
					List<Idea> ideas = this.json.readValue(rs.getString("ideas"), new TypeReference<List<Idea>>() {
					});
					Pool stored = new Pool(List.copyOf(ideas), rs.getTimestamp("made_at").toInstant());
					if (lang.isEnglish()) {
						this.pool = stored;
					}
					else {
						this.translated.put(lang, stored);
					}
				}
				catch (RuntimeException ex) {
					log.warn("Saved ideas in {} not readable: {}", lang.code(), ex.toString());
				}
			});
			this.loaded = true;
		}
	}

	private void save(Lang lang, Pool pool) {
		this.jdbc.update("""
				INSERT INTO idea_pools (language, made_at, ideas) VALUES (?, ?, ?::jsonb)
				ON CONFLICT (language) DO UPDATE SET made_at = excluded.made_at, ideas = excluded.ideas
				""", lang.code(), Timestamp.from(pool.madeAt()), this.json.writeValueAsString(pool.ideas()));
	}

	/**
	 * The pool in {@code lang}: translated once per pool (one AI request); the English pool meanwhile, or if the
	 * translation fails.
	 */
	private Pool translatedPool(Pool english, Lang lang) {
		Pool done = this.translated.get(lang);
		if (done != null && done.madeAt().equals(english.madeAt())) {
			return done;
		}
		ReentrantLock lock = this.translating.computeIfAbsent(lang, l -> new ReentrantLock());
		if (!lock.tryLock()) {
			return english;
		}
		try {
			done = this.translated.get(lang);
			if (done != null && done.madeAt().equals(english.madeAt())) {
				return done;
			}
			List<Idea> ideas = translate(english.ideas(), lang);
			if (ideas == english.ideas()) {
				return english; // not translated this time: English, and the next request tries again
			}
			Pool made = new Pool(ideas, english.madeAt());
			this.translated.put(lang, made);
			save(lang, made);
			return made;
		}
		finally {
			lock.unlock();
		}
	}

	private List<Idea> translate(List<Idea> ideas, Lang lang) {
		StringBuilder request = new StringBuilder("Translate the area, country and summary of each place into ")
			.append(lang.englishName())
			.append(". Keep the ids. Reply with the same number of places, in the same order.\n");
		for (Idea idea : ideas) {
			request.append("- id: ").append(idea.id()).append(" | area: ").append(idea.area())
				.append(" | country: ").append(idea.country()).append(" | summary: ").append(idea.summary())
				.append('\n');
		}
		AiClient.Answer answer = this.ai.generateJson(this.properties.ideaModels(), TRANSLATE_SYSTEM,
				request.toString(), translationSchema(), 0.2, MAX_OUTPUT_TOKENS);
		if (answer == null) {
			return ideas;
		}
		try {
			RawTranslations raw = this.json.readValue(answer.json(), RawTranslations.class);
			Map<String, Translation> byId = new HashMap<>();
			for (Translation t : (raw.places() != null) ? raw.places() : List.<Translation>of()) {
				if (t != null && t.id() != null) {
					byId.put(t.id(), t);
				}
			}
			if (byId.isEmpty()) {
				log.warn("Ideas translation into {}: no places in the answer", lang.code());
				return ideas;
			}
			List<Idea> out = new ArrayList<>();
			for (Idea idea : ideas) {
				Translation t = byId.get(idea.id());
				out.add((t == null) ? idea : new Idea(idea.id(), idea.name(), or(t.area(), idea.area()),
						or(t.country(), idea.country()), idea.category(), idea.kind(), or(t.summary(), idea.summary()),
						idea.lat(), idea.lon(), idea.photo()));
			}
			log.info("Ideas translated into {} ({} of {})", lang.code(), byId.size(), ideas.size());
			return List.copyOf(out);
		}
		catch (RuntimeException ex) {
			log.warn("Ideas translation into {} not usable: {}", lang.code(), ex.toString());
			return ideas;
		}
	}

	private static final String TRANSLATE_SYSTEM = """
			You translate short travel texts for GoAwayGo's travellers. Translate faithfully and naturally, in plain words,
			no marketing language, no emojis. Write place names the way they are usually written in the target language.
			Reply only with the JSON object described.""";

	static Map<String, Object> translationSchema() {
		Map<String, Object> place = Map.of("type", "OBJECT", "properties",
				Map.of("id", Map.of("type", "STRING"), "area", Map.of("type", "STRING"), "country",
						Map.of("type", "STRING"), "summary", Map.of("type", "STRING")),
				"required", List.of("id", "area", "country", "summary"));
		return Map.of("type", "OBJECT", "properties", Map.of("places", Map.of("type", "ARRAY", "items", place)),
				"required", List.of("places"));
	}

	private static String or(String value, String fallback) {
		return (value == null || value.isBlank()) ? fallback : value.strip();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawTranslations(List<Translation> places) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Translation(String id, String area, String country, String summary) {
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
			return (previous != null) ? new Pool(previous.ideas(), madeNow()) : new Pool(List.of(),
					madeNow());
		}
		List<SpotPhotos.Photo> credited = this.photos.withCredits(verified.stream().map(Idea::photo).toList());
		List<Idea> ideas = new ArrayList<>();
		for (int i = 0; i < verified.size(); i++) {
			Idea idea = verified.get(i);
			ideas.add(new Idea(idea.id(), idea.name(), idea.area(), idea.country(), idea.category(), idea.kind(),
					idea.summary(), idea.lat(), idea.lon(), credited.get(i)));
		}
		return new Pool(List.copyOf(ideas), madeNow());
	}

	/** To the millisecond, so a list read back from the database has the same time as its translations. */
	private Instant madeNow() {
		return this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
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
