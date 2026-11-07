package com.goawaygo.backend.book;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;

import com.goawaygo.backend.external.ExternalApis;

/**
 * Cities and airports for the flight search (Aviasales' free autocomplete, no key), and airline names for the
 * results (Aviasales' free airlines file).
 */
@Component
public class Airports {

	static final String AUTOCOMPLETE = "tpautocomplete";

	private static final int SUGGESTIONS = 8;

	private static final Duration AIRLINES_TTL = Duration.ofDays(7);

	private final ExternalApis apis;

	private volatile Map<String, String> airlineNames = Map.of();

	private volatile Instant airlineNamesUntil = Instant.EPOCH;

	public Airports(ExternalApis apis) {
		this.apis = apis;
	}

	/**
	 * A place to fly from or to.
	 *
	 * @param code IATA code (a city code covers all its airports, e.g. LON)
	 * @param type {@code city} or {@code airport}
	 * @param name the city or airport
	 * @param city the airport's city (empty for a city)
	 */
	public record Airport(String code, String type, String name, String city, String country, String countryCode) {
	}

	/** Up to 8 cities and airports matching what the user typed, best first. */
	public List<Airport> suggest(String text) {
		Result[] results = this.apis.client(AUTOCOMPLETE)
			.cached("places:" + text.toLowerCase(Locale.ROOT), Result[].class, (http, key) -> http.get()
				.uri("/places2?term={term}&locale=en&types[]=city&types[]=airport", text)
				.retrieve()
				.body(Result[].class));
		List<Airport> airports = new ArrayList<>();
		if (results != null) {
			for (Result result : results) {
				if (result.code() != null && result.name() != null && airports.size() < SUGGESTIONS) {
					airports.add(new Airport(result.code(), result.type(), result.name(),
							(result.cityName() != null) ? result.cityName() : "",
							(result.countryName() != null) ? result.countryName() : "",
							(result.countryCode() != null) ? result.countryCode() : ""));
				}
			}
		}
		return airports;
	}

	/** Airline names by IATA code ("6E" → "IndiGo"); empty when the file can't be read. Kept in memory for a day. */
	public Map<String, String> airlineNames() {
		Map<String, String> names = this.airlineNames;
		if (names.isEmpty() || Instant.now().isAfter(this.airlineNamesUntil)) {
			names = readAirlineNames();
			if (!names.isEmpty()) {
				this.airlineNames = names;
				this.airlineNamesUntil = Instant.now().plus(Duration.ofDays(1));
			}
		}
		return names;
	}

	private Map<String, String> readAirlineNames() {
		try {
			Airline[] airlines = this.apis.client(FlightPrices.PROVIDER)
				.cached("airlines", AIRLINES_TTL, Airline[].class,
						(http, key) -> http.get().uri("/data/en/airlines.json").retrieve().body(Airline[].class));
			Map<String, String> names = new HashMap<>();
			if (airlines != null) {
				for (Airline airline : airlines) {
					if (airline.code() != null && airline.name() != null) {
						names.put(airline.code(), airline.name());
					}
				}
			}
			return names;
		}
		catch (RuntimeException ex) {
			return Map.of();
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Result(String code, String type, String name, @JsonProperty("city_name") String cityName,
			@JsonProperty("country_name") String countryName, @JsonProperty("country_code") String countryCode) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Airline(String code, String name) {
	}

}
