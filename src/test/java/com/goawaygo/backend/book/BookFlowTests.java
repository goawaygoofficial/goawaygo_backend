package com.goawaygo.backend.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;

/** Book end to end, against a fake Travelpayouts (Aviasales data, autocomplete, links API) on a local port. */
@SpringBootTest
class BookFlowTests extends PostgresIntegrationTest {

	private static final FakeTravelpayouts FAKE = new FakeTravelpayouts();

	private static final LocalDate DEPART = LocalDate.now().plusDays(30);

	private static final String MONTH = YearMonth.from(DEPART).toString();

	/** Another day of the same month as {@link #DEPART}. */
	private static final LocalDate OTHER_DAY = (DEPART.getDayOfMonth() == 1) ? DEPART.plusDays(1) : DEPART.withDayOfMonth(1);

	@DynamicPropertySource
	static void outsideServices(DynamicPropertyRegistry registry) {
		for (String provider : List.of("travelpayouts", "tpautocomplete")) {
			registry.add("goawaygo.external.providers." + provider + ".base-url", FAKE::url);
		}
		registry.add("goawaygo.external.providers.travelpayouts.api-key", () -> "test-token");
		// Trip.com as configured, plus a website-only hotel partner left out of the app (a list is replaced whole).
		registry.add("goawaygo.book.stays[0].id", () -> "tripcom");
		registry.add("goawaygo.book.stays[0].name", () -> "Trip.com");
		registry.add("goawaygo.book.stays[0].url", () -> "https://www.trip.com/hotels/list?searchWord={place}"
				+ "&checkin={checkin}&checkout={checkout}&adult={adults}&crn={rooms}&curr={currency}");
		registry.add("goawaygo.book.stays[0].app-allowed", () -> "true");
		registry.add("goawaygo.book.stays[1].id", () -> "webonly");
		registry.add("goawaygo.book.stays[1].name", () -> "Web Only Hotels");
		registry.add("goawaygo.book.stays[1].url", () -> "https://hotels.example/search?q={place}");
		registry.add("goawaygo.book.stays[1].app-allowed", () -> "false");
	}

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		FAKE.linkRequests.clear();
		FAKE.refuseToken.set(false);
	}

	@Test
	void suggestsCitiesAndAirports() throws Exception {
		this.mvc.perform(get("/api/book/airports").param("q", "del"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].code").value("DEL"))
			.andExpect(jsonPath("$[0].type").value("city"))
			.andExpect(jsonPath("$[0].name").value("New Delhi"))
			.andExpect(jsonPath("$[1].type").value("airport"))
			.andExpect(jsonPath("$[1].city").value("New Delhi"))
			.andExpect(jsonPath("$[1].countryCode").value("IN"));
	}

	@Test
	void flightSearchGivesRecentFaresNearbyDatesAndTrackedLinks() throws Exception {
		String body = this.mvc
			.perform(get("/api/book/flights").param("from", "del").param("to", "BOM")
				.param("depart", DEPART.toString()).param("channel", "app"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.priceKind").value("recent"))
			.andExpect(jsonPath("$.currency").value("INR"))
			.andExpect(jsonPath("$.onDate.length()").value(1))
			.andExpect(jsonPath("$.onDate[0].airline").value("6E"))
			.andExpect(jsonPath("$.onDate[0].airlineName").value("IndiGo"))
			.andExpect(jsonPath("$.onDate[0].price").value(7517))
			.andExpect(jsonPath("$.onDate[0].returnAt").doesNotExist())
			.andExpect(jsonPath("$.nearby.length()").value(1))
			.andExpect(jsonPath("$.nearby[0].price").value(6630))
			.andReturn()
			.getResponse()
			.getContentAsString();

		// Every Aviasales page went through the links API once, with the channel as sub_id.
		String searchLink = JsonPath.read(body, "$.searchLink");
		String fareLink = JsonPath.read(body, "$.onDate[0].link");
		assertThat(searchLink).startsWith("https://tp.example/r?u=")
			.contains(DateTimeFormatter.ofPattern("ddMM").format(DEPART));
		assertThat(fareLink).startsWith("https://tp.example/r?u=").contains("expected_price");
		assertThat(FAKE.linkRequests).hasSize(1);
		assertThat(FAKE.linkRequests.get(0)).contains("\"trs\":597168").contains("\"marker\":798632")
			.contains("\"sub_id\":\"app\"");

		// The same search again: links come from the cache.
		this.mvc.perform(get("/api/book/flights").param("from", "DEL").param("to", "BOM")
			.param("depart", DEPART.toString()).param("channel", "app")).andExpect(status().isOk());
		assertThat(FAKE.linkRequests).hasSize(1);
	}

	@Test
	void priceCalendarListsTheCheapestFarePerDay() throws Exception {
		this.mvc.perform(get("/api/book/flights/calendar").param("from", "DEL").param("to", "BOM").param("month", MONTH))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].price").value(6630))
			.andExpect(jsonPath("$[0].airline").value("6E"));
	}

	@Test
	void staysOpenPartnersOnThePlaceAndDatesAndTheAppGetsOnlyAppPartners() throws Exception {
		LocalDate in = DEPART;
		String web = this.mvc
			.perform(get("/api/book/stays").param("place", "Jaipur, India").param("checkin", in.toString())
				.param("checkout", in.plusDays(2).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value("tripcom"))
			.andExpect(jsonPath("$[1].id").value("webonly"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String tripLink = JsonPath.read(web, "$[0].link");
		String page = URLDecoder.decode(tripLink.substring("https://tp.example/r?u=".length()), StandardCharsets.UTF_8);
		assertThat(page).startsWith("https://www.trip.com/hotels/list?searchWord=Jaipur%2C+India&checkin=" + in)
			.contains("adult=2&crn=1&curr=INR");

		this.mvc
			.perform(get("/api/book/stays").param("place", "Jaipur").param("checkin", in.toString())
				.param("checkout", in.plusDays(2).toString()).param("channel", "app"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("Trip.com"));
	}

	@Test
	void carsUseTheAirportWhenThereIsOneAndPlainPagesWhenAPartnerHasNotApprovedUs() throws Exception {
		String body = this.mvc
			.perform(get("/api/book/cars").param("place", "Goa").param("airport", "goi")
				.param("pickup", DEPART.toString()).param("dropoff", DEPART.plusDays(3).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.contains("discovercars", "economybookings", "qeeq")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		// DiscoverCars hasn't approved us (fake): its plain page is used.
		assertThat((String) JsonPath.read(body, "$[0].link")).isEqualTo("https://www.discovercars.com/");
		assertThat(URLDecoder.decode(JsonPath.read(body, "$[1].link"), StandardCharsets.UTF_8))
			.endsWith("https://www.economybookings.com/en?idpickval=GOI");

		String noAirport = this.mvc
			.perform(get("/api/book/cars").param("place", "Goa").param("pickup", DEPART.toString())
				.param("dropoff", DEPART.plusDays(3).toString()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(URLDecoder.decode(JsonPath.read(noAirport, "$[1].link"), StandardCharsets.UTF_8))
			.endsWith("https://www.economybookings.com/en");
	}

	@Test
	void badSearchesAreRefused() throws Exception {
		this.mvc.perform(get("/api/book/flights").param("from", "Delhi").param("to", "BOM").param("depart", DEPART.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_place"));
		this.mvc.perform(get("/api/book/flights").param("from", "BOM").param("to", "BOM").param("depart", DEPART.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("same_place"));
		this.mvc.perform(get("/api/book/flights").param("from", "DEL").param("to", "BOM").param("depart", "2020-01-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_dates"));
		this.mvc.perform(get("/api/book/stays").param("place", "Jaipur").param("checkin", DEPART.toString())
			.param("checkout", DEPART.toString())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_dates"));
		this.mvc.perform(get("/api/book/cars").param("place", "Goa").param("pickup", DEPART.toString())
			.param("dropoff", DEPART.plusDays(2).toString()).param("channel", "tv")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_channel"));
	}

	@Test
	void aRefusedTokenShowsPricesAsUnavailable() throws Exception {
		FAKE.refuseToken.set(true);
		this.mvc.perform(get("/api/book/flights").param("from", "CCU").param("to", "MAA").param("depart", DEPART.toString()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("flight_prices_unavailable"));
	}

	static final class FakeTravelpayouts {

		final List<String> linkRequests = new CopyOnWriteArrayList<>();

		final AtomicBoolean refuseToken = new AtomicBoolean();

		private final HttpServer server;

		FakeTravelpayouts() {
			try {
				this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			this.server.setExecutor(Executors.newCachedThreadPool());
			this.server.createContext("/", this::handle);
			this.server.start();
		}

		String url() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		private void handle(HttpExchange exchange) throws IOException {
			String path = exchange.getRequestURI().getPath();
			String query = URLDecoder.decode(String.valueOf(exchange.getRequestURI().getRawQuery()),
					StandardCharsets.UTF_8);
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			boolean tokenOk = "test-token".equals(exchange.getRequestHeaders().getFirst("X-Access-Token"))
					&& !this.refuseToken.get();
			int status = 200;
			String answer;
			if (path.equals("/places2")) {
				answer = """
						[{"type":"city","code":"DEL","name":"New Delhi","country_code":"IN","country_name":"India"},
						 {"type":"airport","code":"DEL","name":"Indira Gandhi International Airport","country_code":"IN",
						  "country_name":"India","city_code":"DEL","city_name":"New Delhi"}]""";
			}
			else if (path.equals("/data/en/airlines.json")) {
				answer = "[{\"code\":\"6E\",\"name\":\"IndiGo\"},{\"code\":\"AI\",\"name\":\"Air India\"}]";
			}
			else if (!tokenOk) {
				status = 401;
				answer = "{\"message\":\"Unauthorized\"}";
			}
			else if (path.equals("/aviasales/v3/prices_for_dates")) {
				answer = query.contains("departure_at=" + DEPART) ? prices(price(DEPART, 7517, 0))
						: prices(price(OTHER_DAY, 6630, 1), price(DEPART, 7517, 0));
			}
			else if (path.equals("/aviasales/v3/grouped_prices")) {
				answer = "{\"success\":true,\"data\":{\"" + DEPART + "\":" + price(DEPART, 7517, 0) + ",\"" + OTHER_DAY
						+ "\":" + price(OTHER_DAY, 6630, 1) + "}}";
			}
			else if (path.equals("/links/v1/create")) {
				this.linkRequests.add(body);
				List<String> urls = JsonPath.read(body, "$.links[*].url");
				StringBuilder links = new StringBuilder();
				for (String url : urls) {
					boolean approved = !url.contains("discovercars");
					links.append(links.isEmpty() ? "" : ",")
						.append("{\"url\":\"").append(url.replace("\"", "\\\"")).append("\",\"code\":\"")
						.append(approved ? "success" : "failed").append("\",\"message\":\"")
						.append(approved ? "" : "trs is not subscribed for brand").append("\",\"partner_url\":\"")
						.append(approved ? "https://tp.example/r?u=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8) : "")
						.append("\"}");
				}
				answer = "{\"result\":{\"links\":[" + links + "]},\"code\":\"success\",\"status\":200}";
			}
			else {
				status = 404;
				answer = "{}";
			}
			byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		}

		private static String prices(String... prices) {
			return "{\"success\":true,\"currency\":\"inr\",\"data\":[" + String.join(",", prices) + "]}";
		}

		private static String price(LocalDate day, long price, int transfers) {
			String ddmm = DateTimeFormatter.ofPattern("ddMM").format(day);
			return """
					{"origin":"DEL","destination":"BOM","origin_airport":"DEL","destination_airport":"BOM","price":%d,
					 "airline":"6E","flight_number":"5289","departure_at":"%sT07:10:00+05:30","transfers":%d,
					 "duration":125,"duration_to":125,"link":"/search/DEL%sBOM1?t=abc&expected_price=%d"}"""
				.formatted(price, day, transfers, ddmm, price);
		}

	}

}
