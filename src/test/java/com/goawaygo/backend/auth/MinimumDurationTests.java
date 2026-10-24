package com.goawaygo.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class MinimumDurationTests {

	@Test
	void fastWorkIsPaddedToTheMinimum() {
		long start = System.nanoTime();
		MinimumDuration.run(Duration.ofMillis(200), () -> {
		});
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
	}

	@Test
	void failingWorkIsPaddedTooAndStillThrows() {
		long start = System.nanoTime();
		assertThatIllegalStateException().isThrownBy(() -> MinimumDuration.run(Duration.ofMillis(200), () -> {
			throw new IllegalStateException("smtp down");
		}));
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
	}

	@Test
	void zeroMeansNoWaiting() {
		long start = System.nanoTime();
		MinimumDuration.run(Duration.ZERO, () -> {
		});
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
	}

}
