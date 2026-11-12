package com.goawaygo.backend.external;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The outside services as test runs see them ({@link TestFakes}): each provider's requests answered in memory, in the
 * provider's own format, so the real parsing code runs. Nothing leaves the server. Texts say they are test data; AI
 * answers follow the schema the request asks for ({@link SchemaFaker}). A request this class doesn't know gets a 500
 * that names it, so a new outside call shows up in the test run instead of silently using the real service.
 */
final class FakeHttp implements ClientHttpRequestFactory {

	private static final Logger log = LoggerFactory.getLogger(FakeHttp.class);

	private final String provider;

	private final ObjectMapper json;

	FakeHttp(String provider, ObjectMapper json) {
		this.provider = provider;
		this.json = json;
	}

	@Override
	public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
		return new Request(uri, method);
	}

	/** The fake answer: status and JSON body. */
	record Answer(int status, Object body) {

		static Answer ok(Object body) {
			return new Answer(200, body);
		}

	}

	Answer answer(HttpMethod method, URI uri, byte[] requestBody) {
		String path = (uri.getPath() != null) ? uri.getPath() : "";
		Map<String, String> query = new HashMap<>();
		UriComponentsBuilder.fromUri(uri).build().getQueryParams().forEach((k, v) -> query.put(k, v.isEmpty() ? "" : v.get(0)));
		if (this.provider.startsWith("gemini") && path.endsWith(":generateContent")) {
			return gemini(requestBody);
		}
		return switch (this.provider) {
			case "geoapify" -> geoapify(path, query);
			case "unsplash" -> Answer.ok(Map.of("results", List.of()));
			case "wikipedia", "commons" -> Answer.ok(Map.of("query", Map.of("pages", List.of())));
			case "wikidata" -> Answer.ok(Map.of("results", Map.of("bindings", List.of())));
			case "travelpayouts" -> travelpayouts(path, query, requestBody);
			case "tpautocomplete" -> Answer.ok(List.of(airport("TST", "airport", "Test Airport", "Test City"),
					airport("TCY", "city", "Test City", "Test City")));
			case "openai" -> moderation(requestBody);
			case "fcm" -> Answer.ok(Map.of("name", "projects/test/messages/fake"));
			case "gcpmetadata" -> Answer.ok(Map.of("access_token", "fake-token", "expires_in", 3600));
			default -> null;
		};
	}

	@SuppressWarnings("unchecked")
	private Answer gemini(byte[] requestBody) {
		Map<String, Object> request = this.json.readValue(requestBody, Map.class);
		Object config = request.get("generationConfig");
		Map<String, Object> schema = (config instanceof Map<?, ?> c && c.get("responseSchema") instanceof Map<?, ?> s)
				? (Map<String, Object>) s : null;
		String text = this.json.writeValueAsString(SchemaFaker.fake(schema, "answer", 0));
		return Answer.ok(Map.of("candidates",
				List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", text)))))));
	}

	private Answer geoapify(String path, Map<String, String> query) {
		if (path.endsWith("/place-details")) {
			return Answer.ok(Map.of("features", List.of(Map.of("properties", place(query.getOrDefault("id", "test-1"), 0)))));
		}
		if (path.endsWith("/geocode/autocomplete") || path.endsWith("/geocode/reverse")) {
			List<Object> results = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				results.add(place("test-place-" + (i + 1), i));
			}
			return Answer.ok(Map.of("results", results));
		}
		return null;
	}

	private static Map<String, Object> place(String id, int index) {
		Map<String, Object> place = new LinkedHashMap<>();
		place.put("place_id", id);
		place.put("name", "Test City " + (index + 1));
		place.put("result_type", "city");
		place.put("city", "Test City " + (index + 1));
		place.put("state", "Test State");
		place.put("country", "Testland");
		place.put("country_code", "pt");
		place.put("lat", 38.72 + index);
		place.put("lon", -9.14 + index);
		place.put("formatted", "Test City " + (index + 1) + ", Testland");
		return place;
	}

	@SuppressWarnings("unchecked")
	private Answer travelpayouts(String path, Map<String, String> query, byte[] requestBody) {
		if (path.endsWith("/links/v1/create")) {
			Map<String, Object> request = this.json.readValue(requestBody, Map.class);
			List<Object> links = new ArrayList<>();
			if (request.get("links") instanceof List<?> asked) {
				for (Object link : asked) {
					String url = (link instanceof Map<?, ?> l) ? String.valueOf(l.get("url")) : "";
					links.add(Map.of("url", url, "code", "success", "message", "", "partner_url", url));
				}
			}
			return Answer.ok(Map.of("result", Map.of("links", links)));
		}
		if (path.endsWith("/airlines.json")) {
			return Answer.ok(List.of(Map.of("code", "TS", "name", "Test Air")));
		}
		String origin = query.getOrDefault("origin", "TST");
		String destination = query.getOrDefault("destination", "TCY");
		String departure = query.getOrDefault("departure_at", LocalDate.now().plusDays(30).toString());
		LocalDate day = parseDay(departure);
		if (path.endsWith("/grouped_prices")) {
			Map<String, Object> data = new LinkedHashMap<>();
			for (int i = 0; i < 5; i++) {
				data.put(day.plusDays(i).toString(), price(origin, destination, day.plusDays(i), null, i));
			}
			return Answer.ok(Map.of("data", data));
		}
		if (path.endsWith("/prices_for_dates")) {
			String returnAt = query.get("return_at");
			List<Object> data = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				data.add(price(origin, destination, day, (returnAt != null) ? parseDay(returnAt) : null, i));
			}
			return Answer.ok(Map.of("data", data));
		}
		return null;
	}

	private static LocalDate parseDay(String value) {
		try {
			return (value.length() == 7) ? LocalDate.parse(value + "-01") : LocalDate.parse(value.substring(0, 10));
		}
		catch (RuntimeException ex) {
			return LocalDate.now().plusDays(30);
		}
	}

	private static Map<String, Object> price(String origin, String destination, LocalDate depart, LocalDate back,
			int index) {
		Map<String, Object> price = new LinkedHashMap<>();
		price.put("origin", origin);
		price.put("destination", destination);
		price.put("origin_airport", origin);
		price.put("destination_airport", destination);
		price.put("price", 5000L + 750L * index);
		price.put("airline", "TS");
		price.put("flight_number", String.valueOf(100 + index));
		price.put("departure_at", depart + "T09:00:00+05:30");
		if (back != null) {
			price.put("return_at", back + "T18:00:00+05:30");
			price.put("return_transfers", index % 2);
		}
		price.put("transfers", index % 2);
		price.put("duration", 180 + 30 * index);
		price.put("link", "/search/TEST" + index);
		return price;
	}

	private static Map<String, Object> airport(String code, String type, String name, String city) {
		Map<String, Object> airport = new LinkedHashMap<>();
		airport.put("code", code);
		airport.put("type", type);
		airport.put("name", name);
		airport.put("city_name", city);
		airport.put("country_name", "Testland");
		airport.put("country_code", "PT");
		return airport;
	}

	@SuppressWarnings("unchecked")
	private Answer moderation(byte[] requestBody) {
		Map<String, Object> request = this.json.readValue(requestBody, Map.class);
		int inputs = (request.get("input") instanceof List<?> list) ? Math.max(1, list.size()) : 1;
		List<Object> results = new ArrayList<>();
		for (int i = 0; i < inputs; i++) {
			results.add(Map.of("flagged", false, "categories", Map.of()));
		}
		return Answer.ok(Map.of("results", results));
	}

	private final class Request implements ClientHttpRequest {

		private final URI uri;

		private final HttpMethod method;

		private final HttpHeaders headers = new HttpHeaders();

		private final Map<String, Object> attributes = new HashMap<>();

		private final ByteArrayOutputStream body = new ByteArrayOutputStream();

		Request(URI uri, HttpMethod method) {
			this.uri = uri;
			this.method = method;
		}

		@Override
		public HttpMethod getMethod() {
			return this.method;
		}

		@Override
		public URI getURI() {
			return this.uri;
		}

		@Override
		public Map<String, Object> getAttributes() {
			return this.attributes;
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.headers;
		}

		@Override
		public OutputStream getBody() {
			return this.body;
		}

		@Override
		public ClientHttpResponse execute() {
			Answer answer = answer(this.method, this.uri, this.body.toByteArray());
			if (answer == null) {
				log.error("No fake answer for {} {} {}: add one to FakeHttp", FakeHttp.this.provider, this.method,
						this.uri.getPath());
				answer = new Answer(500, Map.of("error", "no fake answer for " + FakeHttp.this.provider + " "
						+ this.method + " " + this.uri.getPath()));
			}
			return new Response(answer.status(), FakeHttp.this.json.writeValueAsBytes(answer.body()));
		}

	}

	private static final class Response implements ClientHttpResponse {

		private final int status;

		private final byte[] body;

		private final HttpHeaders headers = new HttpHeaders();

		Response(int status, byte[] body) {
			this.status = status;
			this.body = body;
			this.headers.setContentType(MediaType.APPLICATION_JSON);
			this.headers.setContentLength(body.length);
		}

		@Override
		public HttpStatusCode getStatusCode() {
			return HttpStatusCode.valueOf(this.status);
		}

		@Override
		public String getStatusText() {
			HttpStatus known = HttpStatus.resolve(this.status);
			return (known != null) ? known.getReasonPhrase() : "";
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.headers;
		}

		@Override
		public InputStream getBody() {
			return new ByteArrayInputStream(this.body);
		}

		@Override
		public void close() {
		}

	}

}
