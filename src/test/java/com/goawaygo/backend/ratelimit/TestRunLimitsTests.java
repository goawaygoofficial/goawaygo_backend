package com.goawaygo.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.goawaygo.backend.external.TestFakes;

/** Automated test runs (dev and test only: the token never exists in prod) are not held back by our limits. */
class TestRunLimitsTests {

	private static final RateLimitProperties.Limit ONE_PER_HOUR = new RateLimitProperties.Limit(1, Duration.ofHours(1));

	private final RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, ONE_PER_HOUR, ONE_PER_HOUR,
			ONE_PER_HOUR, ONE_PER_HOUR, ONE_PER_HOUR, ONE_PER_HOUR, ONE_PER_HOUR, ONE_PER_HOUR, ONE_PER_HOUR));

	@Test
	void aTestRunIsNotLimitedButEveryoneElseStillIs() {
		this.limiter.consume("plans-create-user", "7", ONE_PER_HOUR);
		assertThatExceptionOfType(RateLimitedException.class)
			.isThrownBy(() -> this.limiter.consume("plans-create-user", "7", ONE_PER_HOUR));

		assertThatCode(() -> TestFakes.during(() -> {
			for (int i = 0; i < 5; i++) {
				this.limiter.consume("plans-create-user", "7", ONE_PER_HOUR);
			}
			return null;
		})).doesNotThrowAnyException();

		// The exhausted bucket is still exhausted for real requests.
		assertThatExceptionOfType(RateLimitedException.class)
			.isThrownBy(() -> this.limiter.consume("plans-create-user", "7", ONE_PER_HOUR));
	}

}
