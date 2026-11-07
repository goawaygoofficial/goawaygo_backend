package com.goawaygo.backend.book;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Hotel and car rental partners for a search: each partner's own search page with the traveller's place and dates
 * filled in, as our affiliate link. No prices: no free price source exists for hotels or cars yet (live prices come
 * with an approved partner API).
 *
 * <p>Partners that allow websites only are left out of the app ({@link BookProperties.Partner#appAllowed()}).
 */
@Component
public class Partners {

	private final BookProperties properties;

	private final PartnerLinks links;

	public Partners(BookProperties properties, PartnerLinks links) {
		this.properties = properties;
		this.links = links;
	}

	/** A partner card: opens the partner's results for the search. */
	public record Card(String id, String name, String link) {
	}

	public List<Card> stays(String place, LocalDate checkin, LocalDate checkout, int adults, int rooms,
			String currency, String channel) {
		Map<String, String> values = Map.of("place", place, "checkin", checkin.toString(), "checkout",
				checkout.toString(), "adults", String.valueOf(adults), "rooms", String.valueOf(rooms), "currency",
				currency, "airport", "");
		return cards(this.properties.stays(), values, channel);
	}

	/**
	 * @param airport the pick-up airport's IATA code, when the traveller picked an airport (some partners open
	 *     straight on it)
	 */
	public List<Card> cars(String place, String airport, LocalDate pickup, LocalDate dropoff, String currency,
			String channel) {
		Map<String, String> values = Map.of("place", place, "checkin", pickup.toString(), "checkout",
				dropoff.toString(), "adults", "1", "rooms", "1", "currency", currency, "airport",
				(airport != null) ? airport : "");
		return cards(this.properties.cars(), values, channel);
	}

	private List<Card> cards(List<BookProperties.Partner> partners, Map<String, String> values, String channel) {
		List<BookProperties.Partner> shown = partners.stream()
			.filter(p -> !"app".equals(channel) || p.appAllowed())
			.toList();
		List<String> pages = new ArrayList<>();
		for (BookProperties.Partner partner : shown) {
			pages.add(page(partner, values));
		}
		List<String> tracked = this.links.track(pages, channel);
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < shown.size(); i++) {
			cards.add(new Card(shown.get(i).id(), shown.get(i).name(), tracked.get(i)));
		}
		return cards;
	}

	static String page(BookProperties.Partner partner, Map<String, String> values) {
		String template = partner.url();
		if (template.contains("{airport}") && values.get("airport").isEmpty() && partner.fallbackUrl() != null
				&& !partner.fallbackUrl().isBlank()) {
			template = partner.fallbackUrl();
		}
		String page = template;
		for (Map.Entry<String, String> value : values.entrySet()) {
			page = page.replace("{" + value.getKey() + "}",
					URLEncoder.encode(value.getValue(), StandardCharsets.UTF_8));
		}
		return page;
	}

}
