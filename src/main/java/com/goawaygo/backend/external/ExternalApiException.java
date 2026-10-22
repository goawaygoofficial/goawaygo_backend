package com.goawaygo.backend.external;

import org.springframework.http.HttpStatus;

import com.goawaygo.backend.common.error.ApiException;

/**
 * An outside API couldn't answer: it failed or timed out after retries, it is temporarily switched off after repeated
 * failures, or we reached the usage limit we set for it. Shown to the client as 503 with a friendly message; callers
 * may catch it and show the page without that information instead.
 */
public class ExternalApiException extends ApiException {

	private static final String MESSAGE = "This information isn't available right now. Please try again later.";

	private final String provider;

	private final String reason;

	private ExternalApiException(String provider, String code, String reason, Throwable cause) {
		super(HttpStatus.SERVICE_UNAVAILABLE, code, MESSAGE);
		this.provider = provider;
		this.reason = reason;
		if (cause != null) {
			initCause(cause);
		}
	}

	static ExternalApiException unavailable(String provider, Throwable cause) {
		return new ExternalApiException(provider, "EXTERNAL_SERVICE_UNAVAILABLE", "unavailable", cause);
	}

	static ExternalApiException limitReached(String provider, String period) {
		return new ExternalApiException(provider, "EXTERNAL_SERVICE_LIMIT", period + " usage limit reached", null);
	}

	public String getProvider() {
		return this.provider;
	}

	/** For logs: which provider and what went wrong (the client sees only the friendly message). */
	@Override
	public String toString() {
		return getClass().getSimpleName() + ": " + this.provider + " " + this.reason;
	}

}
