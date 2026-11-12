package com.goawaygo.backend.external;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Answers from outside APIs, kept in the database ({@code external_api_cache}) so they survive restarts and are
 * shared by every request. Expired answers are kept for a week as a fallback for when the provider is down.
 */
@Component
public class ExternalApiCache {

	/** How long after expiry an answer is still kept as a fallback. */
	static final Duration KEEP_STALE_FOR = Duration.ofDays(7);

	private static final int MAX_KEY_LENGTH = 255;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public ExternalApiCache(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** A cached answer; {@code fresh} is false once it has expired. */
	public record Entry(String body, boolean fresh) {
	}

	public Optional<Entry> find(String provider, String key) {
		if (TestFakes.active()) {
			// Test runs never see real cached answers (nor leave fake ones: see put).
			return Optional.empty();
		}
		Instant now = this.clock.instant();
		return this.jdbc
			.query("SELECT body, expires_at FROM external_api_cache WHERE provider = ? AND cache_key = ?",
					(rs, row) -> new Entry(rs.getString("body"), rs.getTimestamp("expires_at").toInstant().isAfter(now)),
					provider, storedKey(key))
			.stream()
			.findFirst();
	}

	public void put(String provider, String key, String body, Duration ttl) {
		if (TestFakes.active()) {
			// A fake answer must never be served to real users (e.g. an untracked partner link for days).
			return;
		}
		Instant now = this.clock.instant();
		this.jdbc.update("""
				INSERT INTO external_api_cache (provider, cache_key, body, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (provider, cache_key)
				DO UPDATE SET body = EXCLUDED.body, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
				""", provider, storedKey(key), body, Timestamp.from(now), Timestamp.from(now.plus(ttl)));
		// Housekeeping on the way: a few long-expired rows at a time, so the table never grows without bound.
		this.jdbc.update("""
				DELETE FROM external_api_cache WHERE ctid IN (
				    SELECT ctid FROM external_api_cache WHERE expires_at < ? LIMIT 100)
				""", Timestamp.from(now.minus(KEEP_STALE_FOR)));
	}

	/** Long keys (e.g. a full query) are stored as their SHA-256, which also keeps the index small. */
	static String storedKey(String key) {
		if (key.length() <= MAX_KEY_LENGTH) {
			return key;
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return "sha256:" + HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
