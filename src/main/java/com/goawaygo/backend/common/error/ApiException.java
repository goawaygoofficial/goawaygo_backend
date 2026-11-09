package com.goawaygo.backend.common.error;

import com.goawaygo.backend.common.i18n.Lang;
import com.goawaygo.backend.common.i18n.T;

import org.springframework.http.HttpStatus;

/**
 * An expected error with a status and a client-facing code and message. Rendered by {@link GlobalExceptionHandler}
 * in the request's language: {@code message} is the English sentence (the translation key, see {@link T}), with
 * {@code {0}}, {@code {1}}... filled from {@code args}.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final String template;

	private final Object[] args;

	public ApiException(HttpStatus status, String code, String message, Object... args) {
		super(T.fill(message, args));
		this.status = status;
		this.code = code;
		this.template = message;
		this.args = args.clone();
	}

	/** The message in {@code lang}. */
	public String message(Lang lang) {
		return T.t(lang, this.template, this.args);
	}

	public HttpStatus getStatus() {
		return this.status;
	}

	public String getCode() {
		return this.code;
	}

}
