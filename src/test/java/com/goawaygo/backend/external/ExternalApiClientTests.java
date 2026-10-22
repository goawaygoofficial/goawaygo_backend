package com.goawaygo.backend.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;

import com.goawaygo.backend.PostgresIntegrationTest;

/** The toolkit against a fake provider (a local HTTP server) and the real database. */
@SpringBootTest
class ExternalApiClientTests extends PostgresIntegrationTest {

	@Autowired
	private ExternalApiCache cache;

	@Autowired
	private ExternalApiUsage usage;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private JdbcTemplate jdbc;

	private final FakeProvider provider = new FakeProvider();

	/** Each test gets its own provider name, so counters, cache rows and circuit state never leak between tests. */
	private final String name = "test-" + UUID.randomUUID();

	record Place(String name) {
	}

	@BeforeEach
	void start() throws IOException {
		this.provider.start();
	}

	@AfterEach
	void stop() {
		this.provider.stop();
	}

	private ExternalApiClient client(Integer dailyLimit) {
		ExternalApiProperties.Provider config = new ExternalApiProperties.Provider(this.provider.url(), "secret-key",
				Duration.ofSeconds(1), Duration.ofMillis(300), 3, Duration.ofMillis(10), dailyLimit, null,
				Duration.ofHours(1));
		return new ExternalApiClient(this.name, config, this.cache, this.usage, this.json);
	}

	private static final ExternalApiClient.Request<Place> GET_PLACE = (http, key) -> http.get()
		.uri("/place?apikey={key}", key)
		.retrieve()
		.body(Place.class);

	@Test
	void sendsTheKeyAndReturnsTheAnswer() {
		this.provider.respond(200, "{\"name\":\"Goa\"}");

		assertThat(client(null).call(GET_PLACE)).isEqualTo(new Place("Goa"));
		assertThat(this.provider.lastQuery).isEqualTo("apikey=secret-key");
	}

	@Test
	void cachedAnswersAreReusedWithoutCallingAgain() {
		this.provider.respond(200, "{\"name\":\"Goa\"}");
		ExternalApiClient client = client(null);

		assertThat(client.cached("place:goa", Place.class, GET_PLACE)).isEqualTo(new Place("Goa"));
		assertThat(client.cached("place:goa", Place.class, GET_PLACE)).isEqualTo(new Place("Goa"));
		assertThat(this.provider.calls.get()).isEqualTo(1);
	}

	@Test
	void temporaryFailuresAreRetried() {
		this.provider.respond(503, "busy");
		this.provider.respond(429, "slow down");
		this.provider.respond(200, "{\"name\":\"Goa\"}");

		assertThat(client(null).call(GET_PLACE)).isEqualTo(new Place("Goa"));
		assertThat(this.provider.calls.get()).isEqualTo(3);
	}

	@Test
	void timeoutsAreRetriedThenReportedAsUnavailable() {
		this.provider.respond(200, "{\"name\":\"Goa\"}");
		this.provider.delay = Duration.ofSeconds(1); // longer than the 300 ms read timeout

		assertThatExceptionOfType(ExternalApiException.class).isThrownBy(() -> client(null).call(GET_PLACE))
			.satisfies((ex) -> assertThat(ex.getCode()).isEqualTo("EXTERNAL_SERVICE_UNAVAILABLE"))
			.satisfies((ex) -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(this.provider.calls.get()).isEqualTo(3);
	}

	@Test
	void otherClientErrorsAreNotRetriedAndReachTheCaller() {
		this.provider.respond(404, "no such place");

		assertThatExceptionOfType(HttpClientErrorException.NotFound.class)
			.isThrownBy(() -> client(null).call(GET_PLACE));
		assertThat(this.provider.calls.get()).isEqualTo(1);
	}

	@Test
	void stopsAtTheDailyLimitWithoutCallingTheProvider() {
		this.provider.respond(200, "{\"name\":\"Goa\"}");
		ExternalApiClient client = client(3);

		for (int i = 0; i < 3; i++) {
			client.call(GET_PLACE);
		}

		assertThatExceptionOfType(ExternalApiException.class).isThrownBy(() -> client.call(GET_PLACE))
			.satisfies((ex) -> assertThat(ex.getCode()).isEqualTo("EXTERNAL_SERVICE_LIMIT"));
		assertThat(this.provider.calls.get()).isEqualTo(3);
		assertThat(this.usage.callsToday(this.name)).isEqualTo(3);
	}

	@Test
	void everyAttemptCountsTowardsTheLimit() {
		this.provider.respond(503, "busy");
		this.provider.respond(200, "{\"name\":\"Goa\"}");

		client(10).call(GET_PLACE);

		assertThat(this.usage.callsToday(this.name)).isEqualTo(2);
	}

	@Test
	void anExpiredAnswerIsServedWhenTheProviderIsDown() {
		this.cache.put(this.name, "place:goa", "{\"name\":\"Old Goa\"}", Duration.ofSeconds(-1));
		this.provider.respond(500, "down");

		assertThat(client(null).cached("place:goa", Place.class, GET_PLACE)).isEqualTo(new Place("Old Goa"));
	}

	@Test
	void anExpiredAnswerIsRefreshedWhenTheProviderIsUp() {
		this.cache.put(this.name, "place:goa", "{\"name\":\"Old Goa\"}", Duration.ofSeconds(-1));
		this.provider.respond(200, "{\"name\":\"Goa\"}");
		ExternalApiClient client = client(null);

		assertThat(client.cached("place:goa", Place.class, GET_PLACE)).isEqualTo(new Place("Goa"));
		assertThat(this.cache.find(this.name, "place:goa")).hasValueSatisfying((entry) -> {
			assertThat(entry.fresh()).isTrue();
			assertThat(entry.body()).contains("\"Goa\"");
		});
	}

	@Test
	void repeatedFailuresPauseCallsToTheProvider() {
		this.provider.respond(500, "down");
		ExternalApiClient client = client(null);

		// 2 requests x 3 attempts = 6 failed calls: over the 50% threshold once 5 are recorded.
		for (int i = 0; i < 2; i++) {
			assertThatExceptionOfType(ExternalApiException.class).isThrownBy(() -> client.call(GET_PLACE));
		}
		int callsWhenOpened = this.provider.calls.get();

		assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThatExceptionOfType(ExternalApiException.class).isThrownBy(() -> client.call(GET_PLACE));
		assertThat(this.provider.calls.get()).isEqualTo(callsWhenOpened);
	}

	@Test
	void longKeysAreStoredAsAHash() {
		String longKey = "search:" + "x".repeat(400);

		this.cache.put(this.name, longKey, "{}", Duration.ofHours(1));

		assertThat(this.cache.find(this.name, longKey)).isPresent();
		String stored = this.jdbc.queryForObject("SELECT cache_key FROM external_api_cache WHERE provider = ?",
				String.class, this.name);
		assertThat(stored).startsWith("sha256:").hasSize(71);
	}

	/** A local HTTP server that answers with queued responses (the last one repeats). */
	static final class FakeProvider {

		final AtomicInteger calls = new AtomicInteger();

		private final Deque<int[]> statuses = new ArrayDeque<>();

		private final Deque<String> bodies = new ArrayDeque<>();

		volatile Duration delay = Duration.ZERO;

		volatile String lastQuery;

		private HttpServer server;

		void respond(int status, String body) {
			synchronized (this) {
				this.statuses.add(new int[] { status });
				this.bodies.add(body);
			}
		}

		void start() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/", (exchange) -> {
				this.calls.incrementAndGet();
				this.lastQuery = exchange.getRequestURI().getRawQuery();
				int status;
				String body;
				synchronized (this) {
					status = (this.statuses.size() > 1) ? this.statuses.poll()[0] : this.statuses.peek()[0];
					body = (this.bodies.size() > 1) ? this.bodies.poll() : this.bodies.peek();
				}
				try {
					Thread.sleep(this.delay.toMillis());
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				try {
					exchange.sendResponseHeaders(status, bytes.length);
					exchange.getResponseBody().write(bytes);
				}
				catch (IOException ex) {
					// The client gave up (timeout test).
				}
				exchange.close();
			});
			// One thread per request, so slow answers (timeout test) don't queue up behind each other.
			this.server.setExecutor(Executors.newCachedThreadPool());
			this.server.start();
		}

		String url() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		void stop() {
			this.server.stop(0);
		}

	}

}
