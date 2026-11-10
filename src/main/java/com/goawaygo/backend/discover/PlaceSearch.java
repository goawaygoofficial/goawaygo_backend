package com.goawaygo.backend.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;

import com.goawaygo.backend.external.ExternalApis;

/**
 * Finds real places by name with Geoapify (worldwide OpenStreetMap data): suggestions while typing, and the full
 * details of the place the user picked. The Geoapify key never leaves the backend.
 */
@Component
public class PlaceSearch {

	static final String PROVIDER = "geoapify";

	private static final int SUGGESTIONS = 6;

	private final ExternalApis apis;

	public PlaceSearch(ExternalApis apis) {
		this.apis = apis;
	}

	/** Up to 6 places matching what the user typed, best first. */
	public List<Place> suggest(String text) {
		AutocompleteResponse response = this.apis.client(PROVIDER)
			.call((http, key) -> http.get()
				.uri("/v1/geocode/autocomplete?text={text}&limit={limit}&format=json&lang=en&apiKey={key}", text,
						SUGGESTIONS, key)
				.retrieve()
				.body(AutocompleteResponse.class));
		List<Place> places = new ArrayList<>();
		if (response != null && response.results() != null) {
			for (Result result : response.results()) {
				Place place = result.toPlace(result.resultType());
				if (place != null && places.stream().noneMatch(p -> p.label().equals(place.label()))) {
					places.add(place);
				}
			}
		}
		return places;
	}

	/**
	 * The place with this Geoapify id, or null if it doesn't exist. Asks for the place's names in other languages
	 * too (one more credit), because details give the local name ("日本") where suggestions gave the English one.
	 */
	public Place byId(String id) {
		DetailsResponse response = this.apis.client(PROVIDER)
			.call((http, key) -> http.get()
				.uri("/v2/place-details?id={id}&features=details,details.names&lang=en&apiKey={key}", id, key)
				.retrieve()
				.body(DetailsResponse.class));
		if (response == null || response.features() == null || response.features().isEmpty()) {
			return null;
		}
		Result details = response.features().get(0).properties();
		return details.toPlace(null);
	}

	/** A place tag: names only (no coordinates). */
	public record PlaceName(String name, String region, String country) {
	}

	/**
	 * The town or place at a photo's location, for its place tag (Wanderbook). The coordinates are only sent to
	 * Geoapify for this lookup and never stored. Null if nothing is there (the sea) or the lookup fails.
	 */
	public PlaceName nameAt(double lat, double lon) {
		try {
			AutocompleteResponse response = this.apis.client(PROVIDER)
				.call((http, key) -> http.get()
					.uri("/v1/geocode/reverse?lat={lat}&lon={lon}&format=json&lang=en&apiKey={key}", lat, lon, key)
					.retrieve()
					.body(AutocompleteResponse.class));
			if (response == null || response.results() == null || response.results().isEmpty()) {
				return null;
			}
			Result place = response.results().get(0);
			String name = Result.firstNonBlank(place.city(), place.name(), place.state(), place.country());
			if (name.isBlank() || place.country() == null) {
				return null;
			}
			String region = (place.state() != null && !place.state().equalsIgnoreCase(name)) ? place.state().trim() : "";
			return new PlaceName(name, region, place.country().trim());
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	/** The best match for free text (when the user pressed Search without picking a suggestion). */
	public Place bestMatch(String text) {
		List<Place> places = suggest(text);
		return places.isEmpty() ? null : places.get(0);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record AutocompleteResponse(List<Result> results) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record DetailsResponse(List<Feature> features) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(Result properties) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Result(@JsonProperty("place_id") String placeId, String name,
			@JsonProperty("name_international") Map<String, Object> names,
			@JsonProperty("result_type") String resultType, String city, String state, String country,
			@JsonProperty("country_code") String countryCode, Double lat, Double lon, String formatted) {

		/**
		 * @param resultType Geoapify's type when known (search results); for place details it is worked out from
		 *     the fields
		 */
		Place toPlace(String resultType) {
			if (this.placeId == null || this.lat == null || this.lon == null || this.country == null) {
				return null;
			}
			String city = blank(this.city);
			String region = blank(this.state);
			String english = (this.names != null && this.names.get("en") instanceof String en) ? en : null;
			String name = firstNonBlank(english, this.name, city, region, this.country);
			String type = type(resultType, name, city, region);
			// "Paris, Ile-de-France, France": each part once (whole parts compared, so "France" isn't taken as
			// already in "Ile-de-France")
			List<String> parts = new ArrayList<>(List.of(name));
			for (String part : List.of(city, region, this.country)) {
				if (!part.isBlank() && parts.stream().noneMatch(part::equalsIgnoreCase)) {
					parts.add(part);
				}
			}
			return new Place(this.placeId, name, type, "city".equals(type) ? "" : city, region, this.country,
					blank(this.countryCode).toUpperCase(Locale.ROOT), this.lat, this.lon, String.join(", ", parts));
		}

		private String type(String resultType, String name, String city, String region) {
			if (resultType != null) {
				return switch (resultType) {
					case "country" -> "country";
					case "state", "county" -> "region";
					case "city", "suburb", "district", "postcode" -> "city";
					default -> "place";
				};
			}
			if (name.equalsIgnoreCase(this.country) && city.isBlank() && region.isBlank()) {
				return "country";
			}
			if (name.equalsIgnoreCase(region) && city.isBlank()) {
				return "region";
			}
			return name.equalsIgnoreCase(city) ? "city" : "place";
		}

		private static String blank(String value) {
			return (value != null) ? value.trim() : "";
		}

		private static String firstNonBlank(String... values) {
			for (String value : values) {
				if (value != null && !value.isBlank()) {
					return value.trim();
				}
			}
			return "";
		}

	}

}
