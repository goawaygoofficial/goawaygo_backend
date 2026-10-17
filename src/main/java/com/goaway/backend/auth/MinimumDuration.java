package com.goaway.backend.auth;

import java.time.Duration;

/** Runs work and then waits until at least a minimum time has passed, even if the work failed. */
final class MinimumDuration {

	private MinimumDuration() {
	}

	static void run(Duration minimum, Runnable work) {
		long deadline = System.nanoTime() + minimum.toNanos();
		try {
			work.run();
		}
		finally {
			sleepUntil(deadline);
		}
	}

	private static void sleepUntil(long deadlineNanos) {
		long remaining = deadlineNanos - System.nanoTime();
		while (remaining > 0) {
			try {
				Thread.sleep(Math.max(1, remaining / 1_000_000));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			remaining = deadlineNanos - System.nanoTime();
		}
	}

}
