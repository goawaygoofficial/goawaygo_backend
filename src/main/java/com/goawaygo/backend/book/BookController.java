package com.goawaygo.backend.book;

import com.goawaygo.backend.user.Regions;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.ratelimit.RateLimiter;

/**
 * Book (4.3): flight prices and partner links for flights, stays and car rentals. Public, limited per IP. Bookings
 * happen on the partner's site.
 *
 * <p>{@code channel} is {@code app} or {@code web}: the app only gets partners that allow promotion in apps, and each
 * click is counted per channel.
 */
@RestController
@RequestMapping("/api/book")
public class BookController {

	private static final Pattern IATA = Pattern.compile("[A-Z]{3}");

	private static final Pattern CURRENCY = Pattern.compile("[A-Za-z]{3}");

	/** How far ahead travellers can search (partners sell about a year ahead). */
	private static final int MAX_DAYS_AHEAD = 365;

	private static final int MAX_NIGHTS = 30;

	private final Airports airports;

	private final FlightPrices flights;

	private final Partners partners;

	private final RateLimiter rateLimiter;

	private final BookProperties.Limits limits;

	private final Clock clock;

	public BookController(Airports airports, FlightPrices flights, Partners partners, RateLimiter rateLimiter,
			BookProperties properties, Clock clock) {
		this.airports = airports;
		this.flights = flights;
		this.partners = partners;
		this.rateLimiter = rateLimiter;
		this.limits = properties.limits();
		this.clock = clock;
	}

	/** Cities and airports matching what the user is typing (at least 2 characters). */
	@GetMapping("/airports")
	public List<Airports.Airport> airports(@RequestParam("q") String text, HttpServletRequest request) {
		String query = text.trim();
		if (query.length() < 2 || query.length() > 60) {
			throw invalid("invalid_query", "Type 2 to 60 characters.");
		}
		this.rateLimiter.consume("book-search-ip", request.getRemoteAddr(), this.limits.searchPerIp());
		return this.airports.suggest(query);
	}

	/** Recent fares for a trip, cheaper fares nearby and a link to the partner's live search. */
	@GetMapping("/flights")
	public FlightPrices.Results flights(@RequestParam("from") String from, @RequestParam("to") String to,
			@RequestParam("depart") String depart, @RequestParam(name = "return", required = false) String returnDate,
			@RequestParam(name = "adults", defaultValue = "1") int adults,
			@RequestParam(name = "direct", defaultValue = "false") boolean direct,
			@RequestParam(name = "currency", defaultValue = "INR") String currency,
			@RequestParam(name = "channel", defaultValue = "web") String channel, HttpServletRequest request) {
		String origin = iata(from, "from");
		String destination = iata(to, "to");
		if (origin.equals(destination)) {
			throw invalid("same_place", "Pick a different destination.");
		}
		LocalDate departOn = date(depart, "depart");
		LocalDate returnOn = (returnDate == null || returnDate.isBlank()) ? null : date(returnDate, "return");
		if (returnOn != null && returnOn.isBefore(departOn)) {
			throw invalid("invalid_dates", "The return date must be on or after the departure date.");
		}
		if (adults < 1 || adults > 9) {
			throw invalid("invalid_travellers", "Choose 1 to 9 adults.");
		}
		this.rateLimiter.consume("book-flights-ip", request.getRemoteAddr(), this.limits.flightsPerIp());
		return this.flights.search(new FlightPrices.Query(origin, destination, departOn, returnOn, adults, direct,
				currency(currency), channel(channel)));
	}

	/** The cheapest recent fare for each departure day of a month. */
	@GetMapping("/flights/calendar")
	public List<FlightPrices.Day> calendar(@RequestParam("from") String from, @RequestParam("to") String to,
			@RequestParam("month") String month, @RequestParam(name = "direct", defaultValue = "false") boolean direct,
			@RequestParam(name = "currency", defaultValue = "INR") String currency, HttpServletRequest request) {
		String origin = iata(from, "from");
		String destination = iata(to, "to");
		YearMonth yearMonth;
		try {
			yearMonth = YearMonth.parse(month);
		}
		catch (DateTimeParseException ex) {
			throw invalid("invalid_dates", "Month must look like 2026-12.");
		}
		YearMonth now = YearMonth.now(this.clock);
		if (yearMonth.isBefore(now) || yearMonth.isAfter(now.plusMonths(12))) {
			throw invalid("invalid_dates", "Pick a month in the next year.");
		}
		this.rateLimiter.consume("book-flights-ip", request.getRemoteAddr(), this.limits.flightsPerIp());
		return this.flights.calendar(origin, destination, yearMonth, direct, currency(currency));
	}

	/** Hotel partners opened on the place and dates. */
	@GetMapping("/stays")
	public List<Partners.Card> stays(@RequestParam("place") String place, @RequestParam("checkin") String checkin,
			@RequestParam("checkout") String checkout, @RequestParam(name = "adults", defaultValue = "2") int adults,
			@RequestParam(name = "rooms", defaultValue = "1") int rooms,
			@RequestParam(name = "currency", defaultValue = "INR") String currency,
			@RequestParam(name = "channel", defaultValue = "web") String channel, HttpServletRequest request) {
		String where = place(place);
		LocalDate in = date(checkin, "checkin");
		LocalDate out = date(checkout, "checkout");
		stay(in, out);
		if (adults < 1 || adults > 30 || rooms < 1 || rooms > 8 || rooms > adults) {
			throw invalid("invalid_travellers", "Choose 1 to 30 guests and 1 to 8 rooms (no more rooms than guests).");
		}
		this.rateLimiter.consume("book-partners-ip", request.getRemoteAddr(), this.limits.partnersPerIp());
		return this.partners.stays(where, in, out, adults, rooms, currency(currency), channel(channel));
	}

	/** Car rental partners opened on the pick-up place and dates. */
	@GetMapping("/cars")
	public List<Partners.Card> cars(@RequestParam("place") String place,
			@RequestParam(name = "airport", required = false) String airport, @RequestParam("pickup") String pickup,
			@RequestParam("dropoff") String dropoff,
			@RequestParam(name = "currency", defaultValue = "INR") String currency,
			@RequestParam(name = "channel", defaultValue = "web") String channel, HttpServletRequest request) {
		String where = place(place);
		String airportCode = (airport == null || airport.isBlank()) ? null : iata(airport, "airport");
		LocalDate from = date(pickup, "pickup");
		LocalDate until = date(dropoff, "dropoff");
		stay(from, until);
		this.rateLimiter.consume("book-partners-ip", request.getRemoteAddr(), this.limits.partnersPerIp());
		return this.partners.cars(where, airportCode, from, until, currency(currency), channel(channel));
	}

	private static String iata(String code, String field) {
		String upper = (code != null) ? code.trim().toUpperCase(Locale.ROOT) : "";
		if (!IATA.matcher(upper).matches()) {
			throw invalid("invalid_place", "Pick a city or airport for '{0}'.", field);
		}
		return upper;
	}

	private static String place(String place) {
		String where = (place != null) ? place.trim() : "";
		if (where.length() < 2 || where.length() > 100) {
			throw invalid("invalid_place", "Type a place of 2 to 100 characters.");
		}
		return where;
	}

	private LocalDate date(String value, String field) {
		LocalDate date;
		try {
			date = LocalDate.parse(value);
		}
		catch (DateTimeParseException | NullPointerException ex) {
			throw invalid("invalid_dates", "'{0}' must be a date like 2026-12-05.", field);
		}
		LocalDate today = LocalDate.now(this.clock);
		// A day of slack: it may already be tomorrow where the traveller is.
		if (date.isBefore(today.minusDays(1)) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
			throw invalid("invalid_dates", "Pick dates from today to a year ahead.");
		}
		return date;
	}

	private static void stay(LocalDate from, LocalDate until) {
		if (!until.isAfter(from) || until.isAfter(from.plusDays(MAX_NIGHTS))) {
			throw invalid("invalid_dates", "The end date must be 1 to 30 days after the start date.");
		}
	}

	/** A currency in use today (ISO 4217); the apps send the traveller's (4.7). */
	private static String currency(String currency) {
		String code = (currency == null) ? "" : currency.trim().toUpperCase(Locale.ROOT);
		if (!CURRENCY.matcher(code).matches() || !Regions.isCurrency(code)) {
			throw invalid("invalid_currency", "Currency must be a 3-letter code like INR.");
		}
		return code;
	}

	private static String channel(String channel) {
		if (!"app".equals(channel) && !"web".equals(channel)) {
			throw invalid("invalid_channel", "Channel must be app or web.");
		}
		return channel;
	}

	private static ApiException invalid(String code, String message, Object... args) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message, args);
	}

}
