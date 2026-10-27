package com.goawaygo.backend.discover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;

/**
 * Discover end to end, against a fake of every outside service (Geoapify, Gemini, Wikipedia, Wikimedia Commons,
 * Wikidata, Unsplash) running on a local port.
 */
@SpringBootTest
class DiscoverFlowTests extends PostgresIntegrationTest {

	private static final FakeServices FAKE = new FakeServices();

	@DynamicPropertySource
	static void outsideServices(DynamicPropertyRegistry registry) {
		for (String provider : List.of("gemini", "geminilite", "geminilite35", "geoapify", "unsplash", "wikipedia",
				"commons", "wikidata")) {
			registry.add("goawaygo.external.providers." + provider + ".base-url", FAKE::url);
			registry.add("goawaygo.external.providers." + provider + ".api-key", () -> "test-key");
		}
	}

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		FAKE.modelStatus.clear();
	}

	@Test
	void suggestsRealPlacesWhileTyping() throws Exception {
		this.mvc.perform(get("/api/discover/places").param("q", "par"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Paris"))
			.andExpect(jsonPath("$[0].type").value("city"))
			.andExpect(jsonPath("$[0].countryCode").value("FR"))
			.andExpect(jsonPath("$[0].label").value("Paris, Ile-de-France, France"));
	}

	@Test
	void aDestinationPageHasNineCheckedCardsAndSpotPhotos() throws Exception {
		FAKE.modelStatus.put("gemini-3-flash-preview", 503); // overloaded: the next model answers

		String page = this.mvc.perform(get("/api/discover/destination").param("placeId", "pid-paris"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ok"))
			.andExpect(jsonPath("$.model").value("gemini-3.1-flash-lite"))
			.andExpect(jsonPath("$.place.name").value("Paris"))
			.andExpect(jsonPath("$.cards.length()").value(9))
			.andReturn()
			.getResponse()
			.getContentAsString();

		// Each card separately: ids in page order, a broken AI link removed, a price tagged "may vary".
		List<String> ids = JsonPath.read(page, "$.cards[*].id");
		assertThat(ids).containsExactlyElementsOf(DestinationCards.CARD_IDS);
		List<Object> visaLinks = JsonPath.read(page, "$.cards[1].items[*].link");
		assertThat(visaLinks).containsOnlyNulls();
		List<Boolean> transportMayChange = JsonPath.read(page, "$.cards[6].items[*].mayChange");
		assertThat(transportMayChange).containsExactly(true);
		// The empty hotels card is shown as not available; the others are.
		assertThat((Boolean) JsonPath.read(page, "$.cards[4].available")).isFalse();
		// Spots: the AI's attractions with Wikipedia photos (credited), plus Wikidata's spot with an Unsplash photo.
		List<String> spots = JsonPath.read(page, "$.spots[*].name");
		assertThat(spots).containsExactly("Eiffel Tower", "Louvre Museum", "Sainte-Chapelle");
		assertThat((String) JsonPath.read(page, "$.spots[0].photo.credit"))
			.isEqualTo("Photo: Jane & Doe, CC BY-SA 4.0, Wikimedia Commons");
		assertThat((String) JsonPath.read(page, "$.spots[2].photo.credit")).isEqualTo("Photo by Sam on Unsplash");
		assertThat((String) JsonPath.read(page, "$.spots[2].photo.creditUrl"))
			.isEqualTo("https://unsplash.com/@sam?utm_source=goawaygo&utm_medium=referral");
	}

	@Test
	void placesOpenedByIdUseTheirEnglishName() throws Exception {
		this.mvc.perform(get("/api/discover/destination").param("placeId", "pid-japan"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.place.name").value("Japan"))
			.andExpect(jsonPath("$.place.type").value("country"))
			.andExpect(jsonPath("$.place.label").value("Japan"));
	}

	@Test
	void aLandmarksPageStartsWithTheLandmarkItself() throws Exception {
		String page = this.mvc.perform(get("/api/discover/destination").param("placeId", "pid-eiffel"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.place.name").value("Eiffel Tower"))
			.andExpect(jsonPath("$.place.type").value("place"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		// The AI also lists the Eiffel Tower as an attraction: shown once.
		List<String> spots = JsonPath.read(page, "$.spots[*].name");
		assertThat(spots).containsExactly("Eiffel Tower", "Louvre Museum", "Sainte-Chapelle");
	}

	@Test
	void whenNoModelCanAnswerThePageSaysBusyButStillHasPhotos() throws Exception {
		FAKE.modelStatus.put("gemini-3-flash-preview", 503);
		FAKE.modelStatus.put("gemini-3.1-flash-lite", 429);
		FAKE.modelStatus.put("gemini-3.5-flash-lite", 500);

		this.mvc.perform(get("/api/discover/destination").param("q", "paris"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("busy"))
			.andExpect(jsonPath("$.cards.length()").value(0))
			.andExpect(jsonPath("$.spots[0].name").value("Sainte-Chapelle"));
	}

	@Test
	void placesTheAiDoesntKnowAndPlacesThatDontExist() throws Exception {
		this.mvc.perform(get("/api/discover/destination").param("q", "atlantisville"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("unknown"));
		this.mvc.perform(get("/api/discover/destination").param("q", "zzzz"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("place_not_found"));
	}

	@Test
	void signedInSearchesBecomeTheHomeBannerAndNeedSignIn() throws Exception {
		this.mvc.perform(get("/api/discover/recent")).andExpect(status().isUnauthorized());
		String token = signedInUser();

		this.mvc.perform(get("/api/discover/recent").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(get("/api/discover/destination").param("placeId", "pid-paris")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());

		this.mvc.perform(get("/api/discover/recent").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].place.name").value("Paris"))
			.andExpect(jsonPath("$[0].spot").value("Eiffel Tower"))
			.andExpect(jsonPath("$[0].photo.url").value("http://img.test/eiffel.jpg"));
	}

	@Test
	void signedOutBannerPhotosComeFromTheDevicesOwnHistory() throws Exception {
		this.mvc.perform(post("/api/discover/photos").contentType(MediaType.APPLICATION_JSON).content("""
				{"items":[{"spot":"Eiffel Tower","place":"Paris","type":"city","country":"France","lat":48.85,"lon":2.35}]}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].photo.url").value("http://img.test/eiffel.jpg"));
	}

	@Test
	void ideasAreOnlyPlacesWikipediaConfirms() throws Exception {
		String body = this.mvc.perform(get("/api/discover/ideas").param("count", "12"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> names = JsonPath.read(body, "$[*].name");
		assertThat(names).containsExactly("Hallstatt"); // "Nowhereland" has no Wikipedia article: dropped
		assertThat((String) JsonPath.read(body, "$[0].kind")).isEqualTo("hidden gem");
		assertThat((Double) JsonPath.read(body, "$[0].lat")).isEqualTo(47.56);
	}

	@Test
	void savedPlacesBelongToTheirUser() throws Exception {
		String token = signedInUser();
		String other = signedInUser();
		String saveBody = """
				{"key":"hallstatt|austria","name":"Hallstatt","area":"Upper Austria","country":"Austria",
				 "lat":47.56,"lon":13.64,"category":"old town"}""";

		this.mvc.perform(post("/api/me/saved-places").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(saveBody)).andExpect(status().isNoContent());
		this.mvc.perform(post("/api/me/saved-places").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(saveBody)).andExpect(status().isNoContent()); // saving again changes nothing

		String list = this.mvc.perform(get("/api/me/saved-places").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("Hallstatt"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		int id = JsonPath.read(list, "$[0].id");

		this.mvc.perform(delete("/api/me/saved-places/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
			.andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/me/saved-places/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNoContent());
		this.mvc.perform(get("/api/me/saved-places")).andExpect(status().isUnauthorized());
	}

	private String signedInUser() throws Exception {
		String email = "discover-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Traveller\",\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andExpect(status().isCreated());
		String body = this.mvc.perform(post("/api/auth/signin").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	/** A local stand-in for every outside service the Discover feature calls. */
	static final class FakeServices {

		final Map<String, Integer> modelStatus = new ConcurrentHashMap<>();

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
			String query = URLDecoder.decode(String.valueOf(exchange.getRequestURI().getRawQuery()),
					StandardCharsets.UTF_8);
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			int status = 200;
			String answer;
			if (path.equals("/v1/geocode/autocomplete")) {
				answer = query.contains("text=zzzz") ? "{\"results\":[]}"
						: query.contains("text=atlantis") ? results(place("pid-atlantis", "Atlantisville", "city", "", "Nowhere", "Utopia", "ut", 1, 1))
						: results(place("pid-paris", "Paris", "city", "", "Ile-de-France", "France", "fr", 48.8566, 2.3522));
			}
			else if (path.equals("/v2/place-details")) {
				// Details give the local name; the English one only in name_international
				answer = query.contains("id=pid-paris") ? details(place("pid-paris", "Paris", null, "Paris",
						"Ile-de-France", "France", "fr", 48.8566, 2.3522), "Paris")
						: query.contains("id=pid-japan") ? details(place("pid-japan", "日本", null, "", "", "Japan", "jp",
								36.57, 139.24), "Japan")
						: query.contains("id=pid-eiffel") ? details(place("pid-eiffel", "Tour Eiffel", null, "Paris",
								"Ile-de-France", "France", "fr", 48.8583, 2.2945), "Eiffel Tower")
						: "{\"features\":[]}";
			}
			else if (path.startsWith("/models/")) {
				String model = path.substring("/models/".length(), path.indexOf(':'));
				status = this.modelStatus.getOrDefault(model, 200);
				answer = (status != 200) ? "{\"error\":{\"message\":\"busy\"}}"
						: gemini(body.contains("Suggest") ? IDEAS : body.contains("Atlantisville") ? UNKNOWN : CARDS);
			}
			else if (path.equals("/w/api.php") && query.contains("prop=imageinfo")) {
				answer = """
						{"query":{"pages":[{"title":"File:Eiffel.jpg","imageinfo":[{"extmetadata":{
						  "Artist":{"value":"<a href=\\"x\\">Jane &amp; Doe</a>"},"LicenseShortName":{"value":"CC BY-SA 4.0"}}}]}]}}""";
			}
			else if (path.equals("/w/api.php")) {
				answer = query.contains("gsrsearch=Eiffel") ? wiki("Eiffel Tower", 48.8584, 2.2945, "eiffel.jpg", "Eiffel.jpg")
						: query.contains("gsrsearch=Louvre") ? wiki("Louvre", 48.8606, 2.3376, "louvre.jpg", "Louvre.jpg")
						: query.contains("gsrsearch=Hallstatt") ? wiki("Hallstatt", 47.56, 13.64, "hallstatt.jpg", "Hallstatt.jpg")
						: "{\"batchcomplete\":true}";
			}
			else if (path.equals("/sparql")) {
				answer = "{\"results\":{\"bindings\":[{\"itemLabel\":{\"value\":\"Sainte-Chapelle\"}},"
						+ "{\"itemLabel\":{\"value\":\"Q12345\"}}]}}";
			}
			else if (path.equals("/search/photos")) {
				answer = query.contains("Sainte-Chapelle") ? """
						{"results":[{"description":"Inside the Sainte-Chapelle","alt_description":"stained glass",
						  "urls":{"regular":"http://img.test/sainte-chapelle.jpg"},
						  "user":{"name":"Sam","links":{"html":"https://unsplash.com/@sam"}}}]}"""
						: "{\"results\":[{\"description\":\"three people riding bicycles\",\"urls\":{\"regular\":\"http://img.test/x.jpg\"},\"user\":{\"name\":\"X\"}}]}";
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

		private static String results(String place) {
			return "{\"results\":[" + place + "]}";
		}

		private static String place(String id, String name, String type, String city, String state, String country,
				String code, double lat, double lon) {
			return """
					{"place_id":"%s","name":"%s",%s"city":"%s","state":"%s","country":"%s","country_code":"%s",
					 "lat":%s,"lon":%s,"formatted":"%s, %s"}""".formatted(id, name,
					(type != null) ? "\"result_type\":\"" + type + "\"," : "", city, state, country, code, lat, lon,
					name, country);
		}

		private static String details(String place, String englishName) {
			String withNames = "{\"name_international\":{\"en\":\"" + englishName + "\"}," + place.strip().substring(1);
			return "{\"features\":[{\"properties\":" + withNames + "}]}";
		}

		private static String wiki(String title, double lat, double lon, String image, String file) {
			return """
					{"query":{"pages":[{"title":"%s","index":1,"thumbnail":{"source":"http://img.test/%s"},
					 "pageimage":"%s","coordinates":[{"lat":%s,"lon":%s}]}]}}""".formatted(title, image, file, lat, lon);
		}

		private static String gemini(String json) {
			String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escaped + "\"}]}}]}";
		}

		private static final String ITEM = "{\"label\":\"%s\",\"detail\":\"%s\",\"mayChange\":false,\"link\":%s}";

		private static final String CARDS = """
				{"unknownDestination":false,
				 "quickFacts":{"items":[%s]},
				 "visa":{"items":[%s]},
				 "seasons":{"items":[%s]},
				 "attractions":{"items":[%s,%s]},
				 "hotels":{"items":[]},
				 "cuisine":{"items":[%s]},
				 "transport":{"items":[%s]},
				 "insurance":{"items":[%s]},
				 "currency":{"items":[%s]}}""".formatted(
				ITEM.formatted("Country", "France", "null"),
				ITEM.formatted("Schengen Area", "France is in the Schengen area", "\"https://127.0.0.1:9/visa\""),
				ITEM.formatted("Best time to visit", "April to June", "null"),
				ITEM.formatted("Eiffel Tower", "Iron tower with city views", "null"),
				ITEM.formatted("Louvre Museum", "Home of the Mona Lisa", "null"),
				ITEM.formatted("Croissant", "Buttery pastry for breakfast", "null"),
				ITEM.formatted("Metro", "A single ticket costs about 2.15 EUR", "null"),
				ITEM.formatted("Is it required?", "Recommended, not required", "null"),
				ITEM.formatted("Currency", "Euro (EUR)", "null"));

		private static final String UNKNOWN = "{\"unknownDestination\":true}";

		private static final String IDEAS = """
				{"ideas":[
				 {"name":"Hallstatt","area":"Upper Austria","country":"Austria","category":"old town",
				  "kind":"hidden gem","summary":"Lakeside village under the Alps"},
				 {"name":"Nowhereland","area":"Imaginary","country":"Utopia","category":"beach","kind":"popular",
				  "summary":"Does not exist"}]}""";

	}

}
