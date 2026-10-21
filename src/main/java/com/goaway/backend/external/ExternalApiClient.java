package com.goaway.backend.external;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The one way to call an outside API. Every call gets:
 * <ul>
 * <li><b>timeouts</b> (connect and read, per provider);</li>
 * <li><b>retries</b> with growing waits, only for temporary failures (timeouts, network errors, 5xx, 429), never for
 * other 4xx;</li>
 * <li>a <b>circuit breaker</b>: after repeated failures the provider is left alone for a minute instead of every
 * request waiting for timeouts;</li>
 * <li>a <b>usage limit</b> per day and month ({@link ExternalApiUsage}), counted for every attempt;</li>
 * <li>optionally a <b>cache</b> ({@link ExternalApiCache}), with the last answer as a fallback when the provider is
 * down.</li>
 * </ul>
 *
 * <pre class="code">
 * Place place = this.openTripMap.cached("place:" + id, Place.class,
 *         (http, key) -&gt; http.get().uri("/en/places/xid/{id}?apikey={key}", id, key).retrieve().body(Place.class));
 * </pre>
 *
 * When the provider can't answer (and nothing is cached) it throws {@link ExternalApiException} (503 for the client).
 * Other 4xx answers (e.g. 404 for an unknown place) are passed on unchanged for the caller to handle.
 */
public class ExternalApiClient {

	private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);

	/** Failure rate (%) over the last calls that switches the provider off for a while. */
	private static final float FAILURE_RATE_THRESHOLD = 50;

	private static final int SLIDING_WINDOW_CALLS = 10;

	private static final int MINIMUM_CALLS = 5;

	private static final Duration OPEN_FOR = Duration.ofMinutes(1);

	/** A request: the provider's preconfigured client and its key (null if it has none). */
	@FunctionalInterface
	public interface Request<T> {

		T send(RestClient http, String apiKey);

	}

	private final String name;

	private final ExternalApiProperties.Provider config;

	private final ExternalApiCache cache;

	private final ExternalApiUsage usage;

	private final ObjectMapper json;

	private final RestClient http;

	private final CircuitBreaker circuitBreaker;

	private final Retry retry;

	ExternalApiClient(String name, ExternalApiProperties.Provider config, ExternalApiCache cache, ExternalApiUsage usage,
			ObjectMapper json) {
		this.name = name;
		this.config = config;
		this.cache = cache;
		this.usage = usage;
		this.json = json;
		this.http = restClient(config);
		this.circuitBreaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
			.failureRateThreshold(FAILURE_RATE_THRESHOLD)
			.slidingWindowSize(SLIDING_WINDOW_CALLS)
			.minimumNumberOfCalls(MINIMUM_CALLS)
			.waitDurationInOpenState(OPEN_FOR)
			.permittedNumberOfCallsInHalfOpenState(1)
			.recordException(ExternalApiClient::isTemporaryFailure)
			// Our own usage limit says nothing about the provider's health.
			.ignoreExceptions(ExternalApiException.class)
			.build());
		this.circuitBreaker.getEventPublisher().onStateTransition((event) -> {
			switch (event.getStateTransition().getToState()) {
				case OPEN -> log.error("{} keeps failing: calls paused for {}", name, OPEN_FOR);
				case CLOSED -> log.info("{} is answering again", name);
				default -> {
				}
			}
		});
		this.retry = Retry.of(name, RetryConfig.custom()
			.maxAttempts(config.maxAttempts())
			.intervalFunction(IntervalFunction.ofExponentialBackoff(config.retryWait(), 2))
			.retryOnException(ExternalApiClient::isTemporaryFailure)
			.build());
	}

	private static RestClient restClient(ExternalApiProperties.Provider config) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(config.connectTimeout())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(config.readTimeout());
		return RestClient.builder().baseUrl(config.baseUrl()).requestFactory(requestFactory).build();
	}

	/** Timeouts, network errors, 5xx and 429 may succeed on a retry; anything else won't. */
	static boolean isTemporaryFailure(Throwable ex) {
		return ex instanceof ResourceAccessException || ex instanceof IOException
				|| ex instanceof HttpServerErrorException
				|| (ex instanceof HttpClientErrorException client
						&& client.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS));
	}

	public String name() {
		return this.name;
	}

	/** Calls the provider now (no cache), with timeouts, retries, circuit breaker and usage limit. */
	public <T> T call(Request<T> request) {
		Supplier<T> attempt = () -> {
			this.usage.reserve(this.name, this.config);
			return request.send(this.http, this.config.apiKey());
		};
		Supplier<T> guarded = Retry.decorateSupplier(this.retry,
				CircuitBreaker.decorateSupplier(this.circuitBreaker, attempt));
		try {
			return guarded.get();
		}
		catch (ExternalApiException ex) {
			log.error("{} not called: {}", this.name, ex.toString());
			throw ex;
		}
		catch (CallNotPermittedException ex) {
			throw ExternalApiException.unavailable(this.name, ex);
		}
		catch (RuntimeException ex) {
			if (isTemporaryFailure(ex)) {
				log.warn("{} failed after {} attempt(s): {}", this.name, this.config.maxAttempts(), ex.toString());
				throw ExternalApiException.unavailable(this.name, ex);
			}
			throw ex;
		}
	}

	/**
	 * Returns the cached answer for {@code key} while it is fresh (for the provider's {@code cache-ttl}); otherwise
	 * calls the provider and caches the answer. If the provider can't answer, an expired answer is returned instead
	 * when there is one.
	 */
	public <T> T cached(String key, Class<T> type, Request<T> request) {
		return cached(key, this.config.cacheTtl(), type, request);
	}

	public <T> T cached(String key, Duration ttl, Class<T> type, Request<T> request) {
		Optional<ExternalApiCache.Entry> cached = this.cache.find(this.name, key);
		if (cached.isPresent() && cached.get().fresh()) {
			return this.json.readValue(cached.get().body(), type);
		}
		T answer;
		try {
			answer = call(request);
		}
		catch (ExternalApiException ex) {
			if (cached.isPresent()) {
				log.warn("{} unavailable, serving an expired cached answer", this.name);
				return this.json.readValue(cached.get().body(), type);
			}
			throw ex;
		}
		this.cache.put(this.name, key, this.json.writeValueAsString(answer), ttl);
		return answer;
	}

	CircuitBreaker.State circuitState() {
		return this.circuitBreaker.getState();
	}

}
