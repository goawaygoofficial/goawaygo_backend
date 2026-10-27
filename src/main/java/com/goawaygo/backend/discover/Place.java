package com.goawaygo.backend.discover;

/**
 * A real place found by search: what the destination page, its AI prompt and the search history are about.
 *
 * @param id Geoapify place id
 * @param name the place's own name, for example "Paris" or "Taj Mahal"
 * @param type {@code country}, {@code region}, {@code city} or {@code place} (landmark, village, beach, ...)
 * @param city the city it is in (empty for countries and regions)
 * @param region state, province or region (may be empty)
 * @param country country name
 * @param countryCode ISO 3166-1 alpha-2, upper case
 * @param label a one-line description for lists, for example "Agra, Uttar Pradesh, India"
 */
public record Place(String id, String name, String type, String city, String region, String country,
		String countryCode, double lat, double lon, String label) {

	/** How far from the place a photo's subject may be and still count as "at" this place. */
	double photoRadiusKm() {
		return switch (this.type) {
			case "country" -> 2500;
			case "region" -> 400;
			default -> 60;
		};
	}

	/** The place and what it is in, for search engines: "Taj Mahal Agra India". */
	String searchContext() {
		StringBuilder context = new StringBuilder(this.name);
		if (!this.city.isBlank() && !this.city.equalsIgnoreCase(this.name)) {
			context.append(' ').append(this.city);
		}
		if (!this.country.isBlank() && !this.country.equalsIgnoreCase(this.name)) {
			context.append(' ').append(this.country);
		}
		return context.toString();
	}

}
