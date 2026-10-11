package com.goaway.backend.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiting (Bucket4j). Each rule ("signin-ip", "signin-email", ...) keeps one bucket per key
 * (an IP or an email) in a bounded cache; idle buckets expire, so memory stays flat under attack.
 *
 * <p>In-memory: limits are per backend instance. With several instances, swap the cache for a shared store
 * (Bucket4j supports Redis and PostgreSQL) without changing callers.
 */
@Component
public class RateLimiter {

	/** Upper bound on tracked keys per rule; beyond it the least recently used buckets are evicted. */
	private static final long MAX_KEYS_PER_RULE = 100_000;

	private final RateLimitProperties properties;

	private final Map<String, Cache<String, Bucket>> rules = new ConcurrentHashMap<>();

	public RateLimiter(RateLimitProperties properties) {
		this.properties = properties;
	}

	/**
	 * Takes one request from the bucket for ({@code rule}, {@code key}).
	 * @throws RateLimitedException if the limit is exhausted
	 */
	public void consume(String rule, String key, RateLimitProperties.Limit limit) {
		if (!this.properties.enabled()) {
			return;
		}
		Cache<String, Bucket> buckets = this.rules.computeIfAbsent(rule,
				name -> Caffeine.newBuilder()
					.maximumSize(MAX_KEYS_PER_RULE)
					// A bucket idle for a whole period is full again, so it can be dropped.
					.expireAfterAccess(limit.period())
					.build());
		Bucket bucket = buckets.get(key, ignored -> Bucket.builder()
			.addLimit(Bandwidth.builder().capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()).build())
			.build());

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
			throw new RateLimitedException(seconds);
		}
	}

}
