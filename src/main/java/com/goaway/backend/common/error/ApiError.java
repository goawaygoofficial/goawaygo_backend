package com.goaway.backend.common.error;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error body returned by every endpoint, e.g. {@code {"status":401,"error":"invalid_credentials","message":"..."}}.
 *
 * @param status HTTP status code
 * @param error stable machine-readable code for clients to branch on
 * @param message human-readable message that clients may show to users
 * @param fieldErrors per-field validation messages (only for validation errors)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(int status, String error, String message, Map<String, String> fieldErrors) {

	public ApiError(int status, String error, String message) {
		this(status, error, message, Map.of());
	}

}
