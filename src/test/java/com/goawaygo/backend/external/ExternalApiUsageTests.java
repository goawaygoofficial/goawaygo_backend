package com.goawaygo.backend.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.goawaygo.backend.PostgresIntegrationTest;

/** Daily and monthly counters, in UTC, against the real database. */
@SpringBootTest
class ExternalApiUsageTests extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transaction;

	private final String name = "test-" + UUID.randomUUID();

	private ExternalApiUsage usageAt(String instant) {
		return new ExternalApiUsage(this.jdbc, this.transaction, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
	}

	private static ExternalApiProperties.Provider limits(Integer daily, Integer monthly) {
		return new ExternalApiProperties.Provider("http://unused", null, Duration.ofSeconds(1), Duration.ofSeconds(1),
				1, Duration.ofMillis(1), daily, monthly, Duration.ofHours(1));
	}

	@Test
	void theDailyLimitStartsAgainTheNextUtcDay() {
		ExternalApiProperties.Provider config = limits(2, null);
		ExternalApiUsage monday = usageAt("2026-10-19T23:59:00Z");
		monday.reserve(this.name, config);
		monday.reserve(this.name, config);

		assertThatExceptionOfType(ExternalApiException.class).isThrownBy(() -> monday.reserve(this.name, config));

		ExternalApiUsage tuesday = usageAt("2026-10-20T00:00:30Z");
		tuesday.reserve(this.name, config);
		assertThat(tuesday.callsToday(this.name)).isEqualTo(1);
	}

	@Test
	void theMonthlyLimitHoldsAcrossDays() {
		ExternalApiProperties.Provider config = limits(10, 3);
		usageAt("2026-10-01T10:00:00Z").reserve(this.name, config);
		usageAt("2026-10-15T10:00:00Z").reserve(this.name, config);
		usageAt("2026-10-31T10:00:00Z").reserve(this.name, config);

		ExternalApiUsage lastDay = usageAt("2026-10-31T11:00:00Z");
		assertThatExceptionOfType(ExternalApiException.class).isThrownBy(() -> lastDay.reserve(this.name, config))
			.satisfies((ex) -> assertThat(ex.getCode()).isEqualTo("EXTERNAL_SERVICE_LIMIT"));
		// The refused call didn't count against the day either.
		assertThat(lastDay.callsToday(this.name)).isEqualTo(1);
		assertThat(usageAt("2026-11-01T00:00:00Z").callsThisMonth(this.name)).isZero();
	}

	@Test
	void providersWithoutLimitsAreNotCounted() {
		ExternalApiUsage usage = usageAt("2026-10-20T10:00:00Z");

		usage.reserve(this.name, limits(null, null));

		assertThat(usage.callsToday(this.name)).isZero();
	}

}
