package com.goawaygo.backend.book;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.external.ExternalApiClient;
import com.goawaygo.backend.external.ExternalApis;

/**
 * Recent flight prices from Aviasales' Data API (free, through Travelpayouts): the cheapest fares other travellers
 * found in the last days, not a live search. They can change by the time the traveller books, so the apps label them
 * "recent". "See all flights" opens Aviasales' own live search.
 *
 * <p>For an exact date Aviasales keeps only the cheapest fare (one per date), so a search also returns the cheapest
 * fares on other dates of the same month.
 */
@Component
public class FlightPrices {

	static final String PROVIDER = "travelpayouts";

	static final String AVIASALES = "https://www.aviasales.com";

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("ddMM");

	private final ExternalApis apis;

	private final Airports airports;

	private final PartnerLinks links;

	private final BookProperties properties;

	public FlightPrices(ExternalApis apis, Airports airports, PartnerLinks links, BookProperties properties) {
		this.apis = apis;
		this.airports = airports;
		this.links = links;
		this.properties = properties;
	}

	/**
	 * @param from IATA code (city or airport)
	 * @param to IATA code
	 * @param depart departure date
	 * @param returnDate return date, null for one way
	 * @param adults 1-9, for the "See all flights" search
	 * @param direct only non-stop flights
	 * @param currency ISO code, e.g. INR
	 * @param channel {@code app} or {@code web}
	 */
	public record Query(String from, String to, LocalDate depart, LocalDate returnDate, int adults, boolean direct,
			String currency, String channel) {
	}

	/**
	 * One fare as Aviasales last saw it.
	 *
	 * @param departAt departure, local time with offset (ISO 8601)
	 * @param returnAt return departure, null for one way
	 * @param stops stops on the way out
	 * @param returnStops stops on the way back (null for one way)
	 * @param durationMinutes total flying time out (and back, for a return trip)
	 * @param price per adult, in {@link Results#currency()}
	 * @param link our affiliate link to this fare on Aviasales
	 */
	public record Fare(String origin, String destination, String airline, String airlineName, String flightNumber,
			String departAt, String returnAt, int stops, Integer returnStops, Integer durationMinutes, long price,
			String link) {
	}

	/**
	 * @param priceKind always {@code recent} (live prices come with a live partner)
	 * @param onDate the cheapest fares on the dates asked (often one, sometimes none)
	 * @param nearby the cheapest fares on other dates of the same month, cheapest first
	 * @param searchLink our affiliate link to Aviasales' live search for exactly this trip
	 */
	public record Results(String priceKind, String currency, List<Fare> onDate, List<Fare> nearby, String searchLink) {
	}

	/** One day of the price calendar: the cheapest fare seen for a departure that day. */
	public record Day(LocalDate date, long price, int stops, String airline) {
	}

	public Results search(Query query) {
		String currency = query.currency().toLowerCase(Locale.ROOT);
		boolean oneWay = query.returnDate() == null;
		List<Price> exact = prices(query, query.depart().toString(),
				oneWay ? null : query.returnDate().toString(), currency);
		List<Price> month = prices(query, YearMonth.from(query.depart()).toString(),
				oneWay ? null : YearMonth.from(query.returnDate()).toString(), currency);

		List<Price> onDate = exact.stream()
			.filter(p -> p.departureAt() != null && p.departureAt().startsWith(query.depart().toString()))
			.limit(this.properties.maxFares())
			.toList();
		List<Price> nearby = month.stream()
			.filter(p -> p.departureAt() != null && !p.departureAt().startsWith(query.depart().toString()))
			.sorted(Comparator.comparingLong(Price::price))
			.limit(this.properties.maxFares())
			.toList();

		String search = AVIASALES + "/search/" + query.from() + query.depart().format(DAY_MONTH) + query.to()
				+ (oneWay ? "" : query.returnDate().format(DAY_MONTH)) + query.adults();
		List<String> pages = new ArrayList<>();
		pages.add(search);
		onDate.forEach(p -> pages.add(page(p)));
		nearby.forEach(p -> pages.add(page(p)));
		List<String> tracked = this.links.track(pages, query.channel());

		Map<String, String> airlines = this.airports.airlineNames();
		List<Fare> onDateFares = new ArrayList<>();
		for (int i = 0; i < onDate.size(); i++) {
			onDateFares.add(onDate.get(i).toFare(airlines, tracked.get(1 + i), oneWay));
		}
		List<Fare> nearbyFares = new ArrayList<>();
		for (int i = 0; i < nearby.size(); i++) {
			nearbyFares.add(nearby.get(i).toFare(airlines, tracked.get(1 + onDate.size() + i), oneWay));
		}
		return new Results("recent", currency.toUpperCase(Locale.ROOT), onDateFares, nearbyFares, tracked.get(0));
	}

	/** The cheapest fare for each departure day of {@code month} (days nobody searched are missing). */
	public List<Day> calendar(String from, String to, YearMonth month, boolean direct, String currency) {
		String cur = currency.toLowerCase(Locale.ROOT);
		GroupedResponse response;
		try {
			response = client().cached("calendar:" + from + ":" + to + ":" + month + ":" + direct + ":" + cur,
				this.properties.pricesCacheTtl(), GroupedResponse.class, (http, key) -> http.get()
					.uri("/aviasales/v3/grouped_prices?origin={from}&destination={to}&departure_at={month}"
							+ "&group_by=departure_at&direct={direct}&currency={cur}", from, to, month, direct, cur)
					.header("X-Access-Token", key)
					.retrieve()
					.body(GroupedResponse.class));
		}
		catch (HttpClientErrorException ex) {
			throw unavailable();
		}
		List<Day> days = new ArrayList<>();
		if (response != null && response.data() != null) {
			new TreeMap<>(response.data()).forEach((date, price) -> {
				if (price != null && price.price() > 0) {
					days.add(new Day(LocalDate.parse(date), price.price(), price.transfers(), price.airline()));
				}
			});
		}
		return days;
	}

	private List<Price> prices(Query query, String departure, String returnAt, String currency) {
		String key = "prices:" + query.from() + ":" + query.to() + ":" + departure + ":" + returnAt + ":"
				+ query.direct() + ":" + currency;
		PricesResponse response;
		try {
			response = client().cached(key, this.properties.pricesCacheTtl(), PricesResponse.class,
				(http, apiKey) -> http.get()
					.uri(uri -> {
						uri.path("/aviasales/v3/prices_for_dates")
							.queryParam("origin", query.from())
							.queryParam("destination", query.to())
							.queryParam("departure_at", departure)
							.queryParam("one_way", returnAt == null)
							.queryParam("direct", query.direct())
							.queryParam("currency", currency)
							.queryParam("sorting", "price")
							.queryParam("limit", 30);
						if (returnAt != null) {
							uri.queryParam("return_at", returnAt);
						}
						return uri.build();
					})
					.header("X-Access-Token", apiKey)
					.retrieve()
					.body(PricesResponse.class));
		}
		catch (HttpClientErrorException ex) {
			throw unavailable();
		}
		return (response != null && response.data() != null)
				? response.data().stream().filter(p -> p.price() > 0 && p.link() != null).toList() : List.of();
	}

	private ExternalApiClient client() {
		return this.apis.client(PROVIDER);
	}

	/** Travelpayouts refused the request (no key yet, a key it doesn't accept): shown as "not available". */
	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "flight_prices_unavailable",
				"Flight prices aren't available right now. Please try again later.");
	}

	private static String page(Price price) {
		return AVIASALES + (price.link().startsWith("/") ? "" : "/") + price.link();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PricesResponse(List<Price> data) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GroupedResponse(Map<String, Price> data) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Price(@JsonProperty("origin_airport") String originAirport,
			@JsonProperty("destination_airport") String destinationAirport, String origin, String destination,
			long price, String airline, @JsonProperty("flight_number") String flightNumber,
			@JsonProperty("departure_at") String departureAt, @JsonProperty("return_at") String returnAt,
			int transfers, @JsonProperty("return_transfers") Integer returnTransfers, Integer duration,
			@JsonProperty("duration_to") Integer durationTo, String link) {

		Fare toFare(Map<String, String> airlines, String trackedLink, boolean oneWay) {
			String airlineName = airlines.getOrDefault(this.airline, (this.airline != null) ? this.airline : "");
			Integer minutes = (this.duration != null) ? this.duration : this.durationTo;
			return new Fare((this.originAirport != null) ? this.originAirport : this.origin,
					(this.destinationAirport != null) ? this.destinationAirport : this.destination, this.airline,
					airlineName, this.flightNumber, this.departureAt, oneWay ? null : this.returnAt, this.transfers,
					oneWay ? null : this.returnTransfers, minutes, this.price, trackedLink);
		}

	}

}
