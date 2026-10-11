package com.goaway.backend.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code goaway.web.*}: the website that emailed links open (no trailing slash). */
@Validated
@ConfigurationProperties("goaway.web")
public record WebProperties(@NotBlank String baseUrl) {

	/** {@code baseUrl + path}, tolerating a trailing slash in the configured URL. */
	public String url(String path) {
		return this.baseUrl.replaceAll("/+$", "") + path;
	}

}
