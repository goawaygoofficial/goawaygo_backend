package com.goawaygo.backend.common.error;

import org.springframework.http.HttpStatus;

/** An expected error with a status and a client-facing code and message. Rendered by {@link GlobalExceptionHandler}. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

	public String getCode() {
		return this.code;
	}

}
