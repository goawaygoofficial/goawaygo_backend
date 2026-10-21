package com.goaway.backend.external;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts calls to each outside API per UTC day and month ({@code external_api_usage}) and refuses a call once a limit
 * is reached, so a free tier is never exceeded. A call is counted before it is made (failed calls count too, as
 * providers count them), atomically, so concurrent requests can't overshoot.
 */
@Component
public class ExternalApiUsage {

	private static final String RESERVE = """
			INSERT INTO external_api_usage (provider, period, period_start, calls) VALUES (?, ?, ?, 1)
			ON CONFLICT (provider, period, period_start)
			DO UPDATE SET calls = external_api_usage.calls + 1 WHERE external_api_usage.calls < ?
			RETURNING calls
			""";

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public ExternalApiUsage(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.clock = clock;
	}

	/**
	 * Counts one call to {@code provider}.
	 * @throws ExternalApiException if the daily or monthly limit is already reached (nothing is counted then)
	 */
	void reserve(String provider, ExternalApiProperties.Provider config) {
		if (config.dailyLimit() == null && config.monthlyLimit() == null) {
			return;
		}
		LocalDate today = today();
		this.transaction.executeWithoutResult((status) -> {
			// Both counters or neither: a refused monthly reservation rolls the daily one back.
			reserve(provider, "DAY", today, config.dailyLimit());
			reserve(provider, "MONTH", today.withDayOfMonth(1), config.monthlyLimit());
		});
	}

	private void reserve(String provider, String period, LocalDate start, Integer limit) {
		if (limit == null) {
			return;
		}
		List<Integer> calls = this.jdbc.queryForList(RESERVE, Integer.class, provider, period, Date.valueOf(start),
				limit);
		if (calls.isEmpty()) {
			throw ExternalApiException.limitReached(provider, period.toLowerCase());
		}
	}

	/** Calls counted so far today (UTC). */
	public int callsToday(String provider) {
		return calls(provider, "DAY", today());
	}

	/** Calls counted so far this month (UTC). */
	public int callsThisMonth(String provider) {
		return calls(provider, "MONTH", today().withDayOfMonth(1));
	}

	private int calls(String provider, String period, LocalDate start) {
		List<Integer> calls = this.jdbc.queryForList(
				"SELECT calls FROM external_api_usage WHERE provider = ? AND period = ? AND period_start = ?",
				Integer.class, provider, period, Date.valueOf(start));
		return calls.isEmpty() ? 0 : calls.get(0);
	}

	private LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC);
	}

}
