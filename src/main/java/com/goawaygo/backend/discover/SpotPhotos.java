package com.goawaygo.backend.discover;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.goawaygo.backend.external.ExternalApis;

/**
 * Photos of tourist spots, fetched live on every request and never stored:
 * <ul>
 * <li><b>Wikipedia / Wikimedia Commons</b>: the spot's own article photo, accepted only if the article has the same
 * name and is located near the destination (so a wrong place can't appear);</li>
 * <li><b>Unsplash</b>: for spots Wikidata suggests (and spots Wikipedia has no photo for), accepted only if the
 * photo's own description mentions the spot.</li>
 * </ul>
 * Every photo carries its credit (photographer and licence), as both sources require.
 */
@Component
public class SpotPhotos {

	private static final Logger log = LoggerFactory.getLogger(SpotPhotos.class);

	static final String WIKIPEDIA = "wikipedia";

	static final String COMMONS = "commons";

	static final String WIKIDATA = "wikidata";

	static final String UNSPLASH = "unsplash";

	static final int MAX_SPOTS = 8;

	private static final String UNSPLASH_UTM = "?utm_source=goawaygo&utm_medium=referral";

	/** Tourist-spot types for the Wikidata query (tourist attraction, monument, castle, temple, beach, ...). */
	private static final String SPOT_TYPES = "wd:Q570116 wd:Q4989906 wd:Q33506 wd:Q16970 wd:Q23413 wd:Q12518 "
			+ "wd:Q22698 wd:Q12280 wd:Q44539 wd:Q24398318 wd:Q2977 wd:Q16560 wd:Q34038 wd:Q40080 wd:Q8502 wd:Q839954 "
			+ "wd:Q1107656 wd:Q11303 wd:Q207694 wd:Q2319498 wd:Q174782 wd:Q32815 wd:Q179700 wd:Q9259";

	private final ExternalApis apis;

	private final Executor executor;

	private final String userAgent;

	public SpotPhotos(ExternalApis apis, @Qualifier("discoverExecutor") Executor executor,
			DiscoverProperties properties) {
		this.apis = apis;
		this.executor = executor;
		this.userAgent = properties.userAgent();
	}

	/** A photo and who to credit for it. */
	public record Photo(String url, String credit, String creditUrl, String source) {
	}

	/** A tourist spot with its photo. */
	public record Spot(String name, Photo photo) {
	}

	/**
	 * Up to 8 spots with photos: the AI's top attractions first (Wikipedia photos), then the spots Wikidata knows near
	 * the place (Unsplash photos). Spots without a photo are left out.
	 */
	public List<Spot> forDestination(Place place, List<String> aiSpots, List<String> wikidataSpots) {
		List<String> fromAi = new ArrayList<>();
		if ("place".equals(place.type())) {
			// A landmark's page starts with the landmark itself; its "attractions" are often parts of it ("The Summit").
			fromAi.add(place.name());
		}
		aiSpots.stream()
			.filter(spot -> fromAi.stream().noneMatch(s -> NameMatch.similar(s, spot)))
			.limit(6 - fromAi.size())
			.forEach(fromAi::add);
		List<String> fromWikidata = wikidataSpots.stream()
			.filter(hint -> fromAi.stream().noneMatch(spot -> NameMatch.similar(spot, hint)))
			.limit(4)
			.toList();
		List<CompletableFuture<Spot>> lookups = new ArrayList<>();
		fromAi.forEach(spot -> lookups.add(async(() -> firstPhoto(spot,
				() -> wikipediaPhoto(spot, place), () -> unsplashPhoto(spot, place)))));
		fromWikidata.forEach(spot -> lookups.add(async(() -> firstPhoto(spot,
				() -> unsplashPhoto(spot, place), () -> wikipediaPhoto(spot, place)))));
		List<Spot> spots = new ArrayList<>();
		for (CompletableFuture<Spot> lookup : lookups) {
			Spot spot = lookup.join();
			if (spot != null && spots.size() < MAX_SPOTS
					&& spots.stream().noneMatch(s -> s.photo().url().equals(spot.photo().url()))) {
				spots.add(spot);
			}
		}
		if (spots.isEmpty()) {
			// A village or small place without listed spots: a photo of the place itself.
			Spot itself = firstPhoto(place.name(), () -> wikipediaPhoto(place.name(), place),
					() -> unsplashPhoto(place.name(), place));
			if (itself != null) {
				spots.add(itself);
			}
		}
		return withCommonsCredits(spots);
	}

	/** One photo for a spot (home banner, ideas): Wikipedia first, then Unsplash. */
	public Spot photoOf(String spot, Place near) {
		Spot found = firstPhoto(spot, () -> wikipediaPhoto(spot, near), () -> unsplashPhoto(spot, near));
		return (found == null) ? null : withCommonsCredits(List.of(found)).get(0);
	}

	/** A place found on Wikipedia: its article title, location and photo. */
	public record Located(String title, double lat, double lon, Photo photo) {
	}

	/**
	 * Finds a place suggested by the AI (no location known yet): its Wikipedia article must have the same name, a
	 * location and a photo, otherwise it doesn't count as real. Credits not loaded yet: see {@link #withCredits}.
	 */
	public Located locate(String name, String context) {
		try {
			WikiResponse response = this.apis.client(WIKIPEDIA)
				.call((http, key) -> http.get()
					.uri("/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrsearch={q}&gsrlimit=3"
							+ "&prop=pageimages|coordinates&piprop=thumbnail|name&pithumbsize=1280", name + " " + context)
					.header("User-Agent", this.userAgent)
					.retrieve()
					.body(WikiResponse.class));
			if (response == null || response.query() == null || response.query().pages() == null) {
				return null;
			}
			return response.query()
				.pages()
				.stream()
				.sorted((a, b) -> Integer.compare(a.index(), b.index()))
				.filter(page -> page.thumbnail() != null && page.pageimage() != null && page.coordinates() != null
						&& !page.coordinates().isEmpty() && NameMatch.sameName(name, page.title()))
				.findFirst()
				.map(page -> new Located(page.title(), page.coordinates().get(0).lat(), page.coordinates().get(0).lon(),
						new Photo(page.thumbnail().source(), "File:" + page.pageimage(),
								"https://commons.wikimedia.org/wiki/File:" + page.pageimage(), "wikimedia")))
				.orElse(null);
		}
		catch (RuntimeException ex) {
			log.debug("{} not found on Wikipedia: {}", name, ex.toString());
			return null;
		}
	}

	/** The photos with their Wikimedia credits (photographer, licence) filled in. */
	public List<Photo> withCredits(List<Photo> photos) {
		return withCommonsCredits(photos.stream().map(p -> new Spot("", p)).toList()).stream()
			.map(Spot::photo)
			.toList();
	}

	/** Names of well-known spots at the place, from Wikidata (best effort: none if slow or failing). */
	public List<String> wikidataSpots(Place place) {
		double radiusKm = switch (place.type()) {
			case "country" -> 0;
			case "region" -> 40;
			case "city" -> 6;
			default -> 4;
		};
		if (radiusKm == 0) {
			return List.of(); // Too large an area for a quick query; the AI's attractions cover countries.
		}
		int minLanguages = "region".equals(place.type()) ? 15 : 8;
		String query = """
				SELECT ?item ?itemLabel ?links WHERE {
				  SERVICE wikibase:around { ?item wdt:P625 ?loc .
				    bd:serviceParam wikibase:center "Point(%s %s)"^^geo:wktLiteral . bd:serviceParam wikibase:radius "%s" . }
				  ?item wikibase:sitelinks ?links . FILTER(?links >= %d)
				  ?item wdt:P31 ?type . VALUES ?type { %s }
				  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
				} ORDER BY DESC(?links) LIMIT 12""".formatted(place.lon(), place.lat(), radiusKm, minLanguages, SPOT_TYPES);
		try {
			SparqlResponse response = this.apis.client(WIKIDATA)
				.call((http, key) -> http.get()
					.uri("/sparql?format=json&query={query}", query)
					.header("User-Agent", this.userAgent)
					.retrieve()
					.body(SparqlResponse.class));
			List<String> names = new ArrayList<>();
			if (response != null && response.results() != null && response.results().bindings() != null) {
				for (Binding binding : response.results().bindings()) {
					String name = (binding.itemLabel() != null) ? binding.itemLabel().value() : null;
					if (name != null && !name.matches("Q\\d+") && !names.contains(name)) {
						names.add(name);
					}
				}
			}
			return names;
		}
		catch (RuntimeException ex) {
			log.info("Wikidata spots for {} skipped: {}", place.name(), ex.toString());
			return List.of();
		}
	}

	Photo wikipediaPhoto(String spot, Place near) {
		WikiResponse response = this.apis.client(WIKIPEDIA)
			.call((http, key) -> http.get()
				.uri("/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrsearch={q}&gsrlimit=4"
						+ "&prop=pageimages|coordinates&piprop=thumbnail|name&pithumbsize=1280", spot + " " + near.searchContext())
				.header("User-Agent", this.userAgent)
				.retrieve()
				.body(WikiResponse.class));
		if (response == null || response.query() == null || response.query().pages() == null) {
			return null;
		}
		return response.query()
			.pages()
			.stream()
			.sorted((a, b) -> Integer.compare(a.index(), b.index()))
			.filter(page -> page.thumbnail() != null && page.pageimage() != null && page.coordinates() != null
					&& !page.coordinates().isEmpty())
			.filter(page -> NameMatch.sameName(spot, page.title()))
			.filter(page -> Geo.km(near.lat(), near.lon(), page.coordinates().get(0).lat(),
					page.coordinates().get(0).lon()) <= near.photoRadiusKm())
			.findFirst()
			.map(page -> new Photo(page.thumbnail().source(), "File:" + page.pageimage(),
					"https://commons.wikimedia.org/wiki/File:" + page.pageimage(), "wikimedia"))
			.orElse(null);
	}

	Photo unsplashPhoto(String spot, Place near) {
		UnsplashSearch response = this.apis.client(UNSPLASH)
			.call((http, key) -> http.get()
				.uri("/search/photos?query={q}&per_page=5&orientation=landscape&content_filter=high",
						spot + " " + near.name())
				.header("Authorization", "Client-ID " + key)
				.header("Accept-Version", "v1")
				.retrieve()
				.body(UnsplashSearch.class));
		if (response == null || response.results() == null) {
			return null;
		}
		return response.results()
			.stream()
			.filter(r -> r.urls() != null && r.urls().regular() != null && r.user() != null)
			.filter(r -> NameMatch.mentions(spot, Objects.toString(r.description(), "") + " "
					+ Objects.toString(r.altDescription(), "")))
			.findFirst()
			.map(r -> new Photo(r.urls().regular(), "Photo by " + r.user().name() + " on Unsplash",
					((r.user().links() != null) ? r.user().links().html() : "https://unsplash.com") + UNSPLASH_UTM,
					"unsplash"))
			.orElse(null);
	}

	/** Replaces the Wikimedia file names held in {@code credit} with "Photo: author, licence, Wikimedia Commons". */
	private List<Spot> withCommonsCredits(List<Spot> spots) {
		List<String> files = spots.stream()
			.filter(s -> s.photo().source().equals("wikimedia"))
			.map(s -> s.photo().credit())
			.distinct()
			.toList();
		Map<String, String> credits = files.isEmpty() ? Map.of() : commonsCredits(files);
		return spots.stream().map(s -> {
			if (!s.photo().source().equals("wikimedia")) {
				return s;
			}
			String credit = credits.getOrDefault(normalize(s.photo().credit()), "Photo: Wikimedia Commons");
			return new Spot(s.name(), new Photo(s.photo().url(), credit, s.photo().creditUrl(), "wikimedia"));
		}).toList();
	}

	private Map<String, String> commonsCredits(List<String> files) {
		Map<String, String> credits = new HashMap<>();
		try {
			CommonsResponse response = this.apis.client(COMMONS)
				.call((http, key) -> http.get()
					.uri("/w/api.php?action=query&format=json&formatversion=2&prop=imageinfo&iiprop=extmetadata"
							+ "&iiextmetadatafilter=Artist|LicenseShortName&titles={titles}", String.join("|", files))
					.header("User-Agent", this.userAgent)
					.retrieve()
					.body(CommonsResponse.class));
			if (response != null && response.query() != null && response.query().pages() != null) {
				for (CommonsPage page : response.query().pages()) {
					if (page.imageinfo() == null || page.imageinfo().isEmpty()
							|| page.imageinfo().get(0).extmetadata() == null) {
						continue;
					}
					Map<String, MetaValue> meta = page.imageinfo().get(0).extmetadata();
					String artist = plain(meta.get("Artist"));
					String license = plain(meta.get("LicenseShortName"));
					StringBuilder credit = new StringBuilder("Photo: ");
					if (!artist.isBlank()) {
						credit.append(artist).append(", ");
					}
					if (!license.isBlank()) {
						credit.append(license).append(", ");
					}
					credits.put(normalize(page.title()), credit.append("Wikimedia Commons").toString());
				}
			}
		}
		catch (RuntimeException ex) {
			log.info("Photo credits not loaded: {}", ex.toString());
		}
		return credits;
	}

	private static String plain(MetaValue value) {
		if (value == null || value.value() == null) {
			return "";
		}
		String text = HtmlUtils.htmlUnescape(value.value().replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
		return (text.length() > 80) ? text.substring(0, 80).trim() + "…" : text;
	}

	private static String normalize(String fileTitle) {
		return fileTitle.replace('_', ' ').trim();
	}

	private static Spot firstPhoto(String spot, Supplier<Photo> first, Supplier<Photo> second) {
		Photo photo = quietly(first);
		if (photo == null) {
			photo = quietly(second);
		}
		return (photo == null) ? null : new Spot(spot, photo);
	}

	private static Photo quietly(Supplier<Photo> lookup) {
		try {
			return lookup.get();
		}
		catch (RuntimeException ex) {
			log.debug("Photo lookup failed: {}", ex.toString());
			return null;
		}
	}

	private <T> CompletableFuture<T> async(Supplier<T> work) {
		return CompletableFuture.supplyAsync(work, this.executor);
	}

	// ---- response shapes ----

	@JsonIgnoreProperties(ignoreUnknown = true)
	record WikiResponse(WikiQuery query) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record WikiQuery(List<WikiPage> pages) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record WikiPage(String title, int index, Thumbnail thumbnail, String pageimage, List<Coordinates> coordinates) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Thumbnail(String source) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Coordinates(double lat, double lon) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CommonsResponse(CommonsQuery query) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CommonsQuery(List<CommonsPage> pages) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CommonsPage(String title, List<ImageInfo> imageinfo) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ImageInfo(LinkedHashMap<String, MetaValue> extmetadata) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record MetaValue(String value) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SparqlResponse(SparqlResults results) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SparqlResults(List<Binding> bindings) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Binding(Value itemLabel) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Value(String value) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record UnsplashSearch(List<UnsplashPhoto> results) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record UnsplashPhoto(String description,
			@com.fasterxml.jackson.annotation.JsonProperty("alt_description") String altDescription, Urls urls,
			User user) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Urls(String regular) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record User(String name, Links links) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Links(String html) {
	}

}
