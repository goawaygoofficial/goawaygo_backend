package com.goawaygo.backend.plans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;

/** The trip planner end to end, against a fake Gemini, Aviasales autocomplete and OpenAI moderation. */
@SpringBootTest
class PlanFlowTests extends PostgresIntegrationTest {

	private static final FakeServices FAKE = new FakeServices();

	private static final LocalDate START = LocalDate.now().plusDays(20);

	@DynamicPropertySource
	static void outsideServices(DynamicPropertyRegistry registry) {
		for (String provider : List.of("gemini", "geminilite", "geminilite35", "tpautocomplete", "openai")) {
			registry.add("goawaygo.external.providers." + provider + ".base-url", FAKE::url);
			registry.add("goawaygo.external.providers." + provider + ".api-key", () -> "test-key");
		}
		registry.add("goawaygo.external.providers.openai.max-attempts", () -> "1");
	}

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		FAKE.liteBusy.set(false);
		FAKE.requests.clear();
	}

	@Test
	void aPlanIsWrittenSavedAndListedWithItsDatesAndCostEstimate() throws Exception {
		String token = traveller();
		String plan = this.mvc.perform(as(token, post("/api/plans")).contentType(MediaType.APPLICATION_JSON)
			.content(request("Jaipur", 3)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mine").value(true))
			.andExpect(jsonPath("$.shared").value(false))
			.andExpect(jsonPath("$.place.name").value("Jaipur"))
			.andExpect(jsonPath("$.airport").value("JAI"))
			.andExpect(jsonPath("$.title").value("Jaipur in 3 days"))
			.andExpect(jsonPath("$.dayPlans.length()").value(3))
			.andExpect(jsonPath("$.dayPlans[0].date").value(START.toString()))
			.andExpect(jsonPath("$.dayPlans[2].date").value(START.plusDays(2).toString()))
			.andExpect(jsonPath("$.dayPlans[0].morning.place").value("Amber Fort"))
			.andExpect(jsonPath("$.endDate").value(START.plusDays(2).toString()))
			.andExpect(jsonPath("$.currency").value("INR"))
			// 3 days of 2,000 = 6,000; 2 nights of 3,000; per day 2,000
			.andExpect(jsonPath("$.estimate.perDay").value(2000))
			.andExpect(jsonPath("$.estimate.nights").value(2))
			.andExpect(jsonPath("$.estimate.total").value(12000))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(plan, "$.id");
		// The request told the AI the trip, and the Lite model was asked first.
		assertThat(FAKE.requests.get(0)).contains("gemini-3.1-flash-lite").contains("Jaipur").contains("Number of days: 3")
			.contains("couple").contains("food, history");

		this.mvc.perform(as(token, get("/api/plans")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].title").value("Jaipur in 3 days"))
			.andExpect(jsonPath("$[0].days").value(3));
		this.mvc.perform(as(token, get("/api/plans/" + id)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dayPlans[1].title").value("Day 2 in Jaipur"));
	}

	@Test
	void aBusyModelFallsBackToTheNextAndNoAnswerIsAFriendlyError() throws Exception {
		String token = traveller();
		FAKE.liteBusy.set(true);
		this.mvc.perform(as(token, post("/api/plans")).contentType(MediaType.APPLICATION_JSON)
			.content(request("Goa", 2)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.dayPlans.length()").value(2));
	}

	@Test
	void oneDayChangesAndTheOthersStay() throws Exception {
		String token = traveller();
		String id = create(token, "Jaipur", 3);
		this.mvc.perform(as(token, post("/api/plans/" + id + "/days/2")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"wish\":\"more street food\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dayPlans[1].title").value("A new day 2"))
			.andExpect(jsonPath("$.dayPlans[0].title").value("Day 1 in Jaipur"))
			.andExpect(jsonPath("$.dayPlans[2].title").value("Day 3 in Jaipur"));
		String dayRequest = FAKE.requests.get(FAKE.requests.size() - 1);
		assertThat(dayRequest).contains("Day 2 of 3").contains("more street food").contains("Amber Fort");

		this.mvc.perform(as(token, post("/api/plans/" + id + "/days/4")).contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_day"));
	}

	@Test
	void aPlanIsWrittenInTheTravellersLanguageAndCurrency() throws Exception {
		String token = traveller();
		String plan = this.mvc.perform(as(token, post("/api/plans")).header(HttpHeaders.ACCEPT_LANGUAGE, "hi")
			.contentType(MediaType.APPLICATION_JSON)
			.content(request("Jaipur", 3).replace("\"interests\"", "\"currency\":\"eur\",\"interests\"")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.currency").value("EUR"))
			.andReturn().getResponse().getContentAsString();
		assertThat(FAKE.requests.get(FAKE.requests.size() - 1)).contains("Currency for all costs: EUR")
			.contains("Language for all text: Hindi");

		// A changed day keeps the plan's language, whatever the request says.
		this.mvc.perform(as(token, post("/api/plans/" + JsonPath.read(plan, "$.id") + "/days/1"))
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());
		assertThat(FAKE.requests.get(FAKE.requests.size() - 1)).contains("Language for all text: Hindi")
			.contains("Currency for all costs: EUR");

		this.mvc.perform(as(token, post("/api/plans")).contentType(MediaType.APPLICATION_JSON)
			.content(request("Jaipur", 3).replace("\"interests\"", "\"currency\":\"ABC\",\"interests\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_currency"));
	}

	@Test
	void aSharedPlanOpensForAnyoneAfterAContentCheckAndOthersCantChangeIt() throws Exception {
		String asha = traveller();
		String ben = traveller();
		String id = create(asha, "Jaipur", 2);

		// Not shared yet: nobody else sees it.
		this.mvc.perform(get("/api/plans/shared/" + id)).andExpect(status().isNotFound());
		this.mvc.perform(as(ben, get("/api/plans/" + id))).andExpect(status().isNotFound());

		this.mvc.perform(as(asha, put("/api/plans/" + id + "/share")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"shared\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.shared").value(true));
		assertThat(FAKE.requests).anyMatch(r -> r.contains("omni-moderation") && r.contains("Amber Fort"));

		this.mvc.perform(get("/api/plans/shared/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mine").value(false))
			.andExpect(jsonPath("$.title").value("Jaipur in 2 days"));
		this.mvc.perform(as(ben, post("/api/plans/" + id + "/days/1")).contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isNotFound());
		this.mvc.perform(as(ben, delete("/api/plans/" + id))).andExpect(status().isNotFound());

		// A shared plan's changed day is checked too: refused content isn't saved.
		this.mvc.perform(as(asha, post("/api/plans/" + id + "/days/1")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"wish\":\"FORBIDDEN\"}")).andExpect(status().isUnprocessableContent());
		this.mvc.perform(get("/api/plans/shared/" + id))
			.andExpect(jsonPath("$.dayPlans[0].title").value("Day 1 in Jaipur"));

		this.mvc.perform(as(asha, put("/api/plans/" + id + "/share")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"shared\":false}")).andExpect(status().isOk());
		this.mvc.perform(get("/api/plans/shared/" + id)).andExpect(status().isNotFound());

		this.mvc.perform(as(asha, delete("/api/plans/" + id))).andExpect(status().isNoContent());
		this.mvc.perform(as(asha, get("/api/plans/" + id))).andExpect(status().isNotFound());
	}

	@Test
	void badRequestsAndSignedOutUsersAreRefused() throws Exception {
		this.mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(request("Jaipur", 3)))
			.andExpect(status().isUnauthorized());
		String token = traveller();
		this.mvc.perform(as(token, post("/api/plans")).contentType(MediaType.APPLICATION_JSON)
			.content(request("Jaipur", 11)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_days"));
		this.mvc.perform(as(token, post("/api/plans")).contentType(MediaType.APPLICATION_JSON)
			.content(request("Jaipur", 3).replace("\"couple\"", "\"aliens\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_travellers"));
		this.mvc.perform(as(token, post("/api/plans")).contentType(MediaType.APPLICATION_JSON)
			.content(request("Jaipur", 3).replace(START.toString(), "2020-01-01")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_dates"));
	}

	private String create(String token, String place, int days) throws Exception {
		String plan = this.mvc.perform(as(token, post("/api/plans")).contentType(MediaType.APPLICATION_JSON)
			.content(request(place, days))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(plan, "$.id");
	}

	private static String request(String place, int days) {
		return """
				{"place":{"placeId":"pid-%s","name":"%s","area":"Rajasthan","country":"India","lat":26.9,"lon":75.8},
				 "startDate":"%s","days":%d,"travellers":"couple","pace":"balanced","budget":"mid",
				 "interests":["food","history"]}""".formatted(place.toLowerCase(), place, START, days);
	}

	private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private String traveller() throws Exception {
		String email = "plans-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Planner\",\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andExpect(status().isCreated());
		String session = this.mvc.perform(post("/api/auth/signin").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(session, "$.accessToken");
	}

	static final class FakeServices {

		final List<String> requests = new CopyOnWriteArrayList<>();

		final AtomicBoolean liteBusy = new AtomicBoolean();

		private static final Pattern DAYS = Pattern.compile("Number of days: (\\d+)");

		private final HttpServer server;

		FakeServices() {
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
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			int status = 200;
			String answer;
			if (path.startsWith("/models/")) {
				String model = path.substring("/models/".length(), path.indexOf(':'));
				this.requests.add(model + " " + body);
				if (this.liteBusy.get() && model.contains("lite")) {
					status = 503;
					answer = "{\"error\":{\"message\":\"busy\"}}";
				}
				else if (body.contains("Rewrite one day")) {
					String title = body.contains("FORBIDDEN") ? "FORBIDDEN day" : "A new day 2";
					answer = gemini(day(title));
				}
				else {
					Matcher days = DAYS.matcher(body);
					int count = days.find() ? Integer.parseInt(days.group(1)) : 1;
					String place = body.contains("Goa") ? "Goa" : "Jaipur";
					List<String> list = new ArrayList<>();
					for (int i = 1; i <= count; i++) {
						list.add(day("Day " + i + " in " + place));
					}
					answer = gemini("""
							{"title":"%s in %d days","summary":"Forts, food and bazaars.","days":[%s],
							 "tips":["Carry cash for bazaars","Start early in summer"],"stayPerNight":3000}"""
						.formatted(place, count, String.join(",", list)));
				}
			}
			else if (path.equals("/places2")) {
				answer = """
						[{"type":"city","code":"JAI","name":"Jaipur","country_name":"India","country_code":"IN"},
						 {"type":"airport","code":"JAI","name":"Jaipur International Airport","country_name":"India"}]""";
			}
			else if (path.equals("/moderations")) {
				this.requests.add("omni-moderation " + body);
				boolean flagged = body.contains("FORBIDDEN");
				answer = "{\"results\":[{\"flagged\":%s,\"categories\":{\"violence\":%s}}]}".formatted(flagged, flagged);
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

		private static String day(String title) {
			return """
					{"title":"%s","morning":{"place":"Amber Fort","activity":"Walk the ramparts.","cost":500},
					 "afternoon":{"place":"City Palace","activity":"Museum and courtyards.","cost":700},
					 "evening":{"place":"Johari Bazaar","activity":"Shop for jewellery.","cost":0},
					 "food":["Dal baati churma","Lassi"],"tip":"Hire a guide at the gate.","cost":2000}""".formatted(title);
		}

		private static String gemini(String json) {
			String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escaped + "\"}]}}]}";
		}

	}

}
