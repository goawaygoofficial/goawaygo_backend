package com.goawaygo.backend.external;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fake outside services for automated and performance test runs (MVP 2, 5.5 and 5.6). A request carrying the header
 * {@value #HEADER} with this environment's token is answered by {@link FakeHttp} instead of the real AI, places,
 * photos, flight prices, partner links, moderation and push services: instant, no free quota used, nothing cached.
 * People using dev or test (no header) still get the real services.
 *
 * <p>The token ({@code TEST_FAKES_TOKEN}) is set only in dev and test, from Secret Manager in goawaygo-staging. Prod
 * has none, so the header does nothing there. Only the request's own thread is faked: work that continues in the
 * background (emails after the commit, pushes) uses the real services.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20) // after the request ID, so fake answers are logged with it
public class TestFakes extends OncePerRequestFilter {

	public static final String HEADER = "X-GoAwayGo-Test";

	/** Shorter tokens are ignored: guessing one must stay impractical. */
	static final int MIN_TOKEN_LENGTH = 24;

	private static final Logger log = LoggerFactory.getLogger(TestFakes.class);

	private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

	/** When a test run's request arrived (System.nanoTime), for {@link ServerTiming}. */
	static final String STARTED = TestFakes.class.getName() + ".started";

	private final byte[] token;

	public TestFakes(@Value("${goawaygo.test-fakes.token:}") String token) {
		String trimmed = (token != null) ? token.trim() : "";
		if (!trimmed.isEmpty() && trimmed.length() < MIN_TOKEN_LENGTH) {
			log.warn("Test fakes off: the token is shorter than {} characters", MIN_TOKEN_LENGTH);
			trimmed = "";
		}
		this.token = trimmed.isEmpty() ? null : trimmed.getBytes(StandardCharsets.UTF_8);
		if (this.token != null) {
			log.info("Test fakes on: requests with {} get fake outside services", HEADER);
		}
	}

	/** True while the current thread handles a test run's request. */
	public static boolean active() {
		return Boolean.TRUE.equals(ACTIVE.get());
	}

	/**
	 * Carries a test run onto another thread: {@code task} uses the fakes if the thread that hands it over does. For
	 * executors that do a request's work in parallel ({@code ThreadPoolTaskExecutor.setTaskDecorator}).
	 */
	public static Runnable carry(Runnable task) {
		if (!active()) {
			return task;
		}
		return () -> during(() -> {
			task.run();
			return null;
		});
	}

	/** Runs {@code work} as if it were a test run's request (for this application's own tests). */
	public static <T> T during(Supplier<T> work) {
		Boolean before = ACTIVE.get();
		ACTIVE.set(true);
		try {
			return work.get();
		}
		finally {
			if (before == null) {
				ACTIVE.remove();
			}
			else {
				ACTIVE.set(before);
			}
		}
	}

	boolean matches(String value) {
		return this.token != null && value != null
				&& MessageDigest.isEqual(this.token, value.trim().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!matches(request.getHeader(HEADER))) {
			chain.doFilter(request, response);
			return;
		}
		ACTIVE.set(true);
		request.setAttribute(STARTED, System.nanoTime());
		// Tells the test run its answers are fake (and that the token was accepted).
		response.setHeader(HEADER, "fakes");
		try {
			chain.doFilter(request, response);
		}
		finally {
			ACTIVE.remove();
		}
	}

}
