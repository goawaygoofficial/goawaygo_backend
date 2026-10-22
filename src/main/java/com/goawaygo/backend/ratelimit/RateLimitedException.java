package com.goawaygo.backend.ratelimit;

import org.springframework.http.HttpStatus;

import com.goawaygo.backend.common.error.ApiException;

/** 429: a rate limit was exceeded. Rendered with a {@code Retry-After} header by the global exception handler. */
public class RateLimitedException extends ApiException {

	private final long retryAfterSeconds;

	public RateLimitedException(long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", "Too many attempts. Please wait and try again.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return this.retryAfterSeconds;
	}

}
