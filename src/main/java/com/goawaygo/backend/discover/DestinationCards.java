package com.goawaygo.backend.discover;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The 9 AI-written info cards of a destination page: one request (prompts in {@code resources/prompts}, explained in
 * {@code docs/destination-prompts.md}), one separate JSON field per card, and every card checked on its own before
 * anything is shown. Nothing is stored: every page open asks again.
 */
@Component
public class DestinationCards {

	private static final Logger log = LoggerFactory.getLogger(DestinationCards.class);

	/** Card ids, in page order; also the JSON field names the AI answers with. */
	public static final List<String> CARD_IDS = List.of("quickFacts", "visa", "seasons", "attractions", "hotels",
			"cuisine", "transport", "insurance", "currency");

	static final int MAX_ITEMS = 7;

	static final int MAX_LABEL_WORDS = 6;

	static final int MAX_DETAIL_WORDS = 35;

	private static final double TEMPERATURE = 0.1;

	private static final int MAX_OUTPUT_TOKENS = 16_000;

	private static final Pattern URL_IN_TEXT = Pattern.compile("(https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

	/**
	 * Details that change over time: amounts with a currency, times of day (not time zones like UTC+5:30), years,
	 * percentages. Items containing them are tagged "may vary" even if the AI forgot to say so.
	 */
	private static final Pattern CHANGING = Pattern.compile("(\\d[\\d.,]*\\s?(%|percent|(?-i:[A-Z]{3})\\b|dollars?|euros?|"
			+ "rupees?|yen|pounds?))|([$€£¥₹₩₽]\\s?\\d)|(\\b\\d{1,2}(:\\d{2})?\\s?(am|pm)\\b)|((?<![+−-])\\b\\d{1,2}:\\d{2}\\b)|"
			+ "(\\b(19|20)\\d{2}\\b)", Pattern.CASE_INSENSITIVE);

	private final AiClient ai;

	private final LinkChecker links;

	private final ObjectMapper json;

	private final DiscoverProperties properties;

	private final String systemPrompt = prompt("prompts/destination-cards-system.txt");

	private final String requestPrompt = prompt("prompts/destination-cards-request.txt");

	public DestinationCards(AiClient ai, LinkChecker links, ObjectMapper json, DiscoverProperties properties) {
		this.ai = ai;
		this.links = links;
		this.json = json;
		this.properties = properties;
	}

	/** One item of a card. {@code mayChange}: shown with "may vary, check before you go". */
	public record Item(String label, String detail, boolean mayChange, String link) {
	}

	/** A card; {@code available} false: shown as "Not available right now". */
	public record Card(String id, boolean available, List<Item> items) {
	}

	/**
	 * @param status {@code ok}, {@code unknown} (the AI doesn't know the place) or {@code busy} (no model could
	 *     answer)
	 * @param model the model that wrote the cards (null unless ok)
	 */
	public record Result(String status, String model, List<Card> cards) {

		static Result busy() {
			return new Result("busy", null, List.of());
		}

		/** The names in the Top Attractions card, best known first. */
		List<String> attractionNames() {
			return this.cards.stream()
				.filter(card -> card.id().equals("attractions"))
				.flatMap(card -> card.items().stream())
				.map(Item::label)
				.toList();
		}

	}

	public Result forPlace(Place place) {
		AiClient.Answer answer = this.ai.generateJson(this.properties.cardModels(), this.systemPrompt, request(place),
				schema(), TEMPERATURE, MAX_OUTPUT_TOKENS);
		if (answer == null) {
			return Result.busy();
		}
		RawAnswer raw;
		try {
			raw = this.json.readValue(answer.json(), RawAnswer.class);
		}
		catch (RuntimeException ex) {
			log.warn("{} answered with JSON that doesn't match the card format: {}", answer.model(), ex.toString());
			return Result.busy();
		}
		if (Boolean.TRUE.equals(raw.unknownDestination())) {
			return new Result("unknown", null, List.of());
		}
		List<Card> cards = new ArrayList<>();
		Map<String, List<RawItem>> byId = raw.byId();
		for (String id : CARD_IDS) {
			cards.add(check(id, byId.get(id)));
		}
		cards = this.links.keepWorkingLinks(cards);
		long available = cards.stream().filter(Card::available).count();
		if (available < CARD_IDS.size()) {
			log.info("{}: {} of {} cards passed the checks", place.name(), available, CARD_IDS.size());
		}
		return new Result("ok", answer.model(), cards);
	}

	String request(Place place) {
		return this.requestPrompt.replace("{name}", place.name())
			.replace("{type}", place.type())
			.replace("{region}", place.region().isBlank() ? "-" : place.region())
			.replace("{country}", place.country())
			.replace("{countryCode}", place.countryCode())
			.replace("{lat}", String.format(Locale.ROOT, "%.4f", place.lat()))
			.replace("{lon}", String.format(Locale.ROOT, "%.4f", place.lon()));
	}

	/** Checks one card: word limits, no addresses in the text, links only as https, changing details tagged. */
	static Card check(String id, List<RawItem> rawItems) {
		List<Item> items = new ArrayList<>();
		if (rawItems != null) {
			for (RawItem raw : rawItems) {
				if (raw == null || items.size() == MAX_ITEMS) {
					continue;
				}
				String label = clean(raw.label());
				String detail = clean(URL_IN_TEXT.matcher((raw.detail() != null) ? raw.detail() : "").replaceAll(""));
				if (label.isEmpty() || detail.isEmpty() || words(label) > MAX_LABEL_WORDS
						|| words(detail) > MAX_DETAIL_WORDS) {
					continue;
				}
				String link = (raw.link() != null) ? raw.link().trim() : "";
				link = (link.startsWith("https://") && link.length() <= 300 && !link.contains(" ")) ? link : null;
				boolean mayChange = Boolean.TRUE.equals(raw.mayChange()) || CHANGING.matcher(detail).find();
				items.add(new Item(label, detail, mayChange, link));
			}
		}
		return new Card(id, !items.isEmpty(), List.copyOf(items));
	}

	private static String clean(String text) {
		return (text == null) ? "" : text.replaceAll("[*_#`]", "").replaceAll("\\s+", " ").trim();
	}

	private static int words(String text) {
		return text.isBlank() ? 0 : text.trim().split("\\s+").length;
	}

	/** The answer format, as Gemini's response schema: one field per card. */
	static Map<String, Object> schema() {
		Map<String, Object> item = Map.of("type", "OBJECT", "properties",
				Map.of("label", Map.of("type", "STRING"), "detail", Map.of("type", "STRING"), "mayChange",
						Map.of("type", "BOOLEAN"), "link", Map.of("type", "STRING", "nullable", true)),
				"required", List.of("label", "detail", "mayChange"));
		Map<String, Object> card = Map.of("type", "OBJECT", "properties",
				Map.of("items", Map.of("type", "ARRAY", "items", item)), "required", List.of("items"));
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("unknownDestination", Map.of("type", "BOOLEAN"));
		for (String id : CARD_IDS) {
			properties.put(id, card);
		}
		List<String> fields = new ArrayList<>();
		fields.add("unknownDestination");
		fields.addAll(CARD_IDS);
		return Map.of("type", "OBJECT", "properties", properties, "required", fields, "propertyOrdering", fields);
	}

	static String prompt(String path) {
		try {
			return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawAnswer(Boolean unknownDestination, RawCard quickFacts, RawCard visa, RawCard seasons,
			RawCard attractions, RawCard hotels, RawCard cuisine, RawCard transport, RawCard insurance,
			RawCard currency) {

		Map<String, List<RawItem>> byId() {
			Map<String, List<RawItem>> cards = new LinkedHashMap<>();
			cards.put("quickFacts", items(this.quickFacts));
			cards.put("visa", items(this.visa));
			cards.put("seasons", items(this.seasons));
			cards.put("attractions", items(this.attractions));
			cards.put("hotels", items(this.hotels));
			cards.put("cuisine", items(this.cuisine));
			cards.put("transport", items(this.transport));
			cards.put("insurance", items(this.insurance));
			cards.put("currency", items(this.currency));
			return cards;
		}

		private static List<RawItem> items(RawCard card) {
			return (card != null) ? card.items() : null;
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawCard(List<RawItem> items) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawItem(String label, String detail, Boolean mayChange, String link) {
	}

}
