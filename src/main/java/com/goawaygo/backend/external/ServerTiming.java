package com.goawaygo.backend.external;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Test runs only (MVP 2, 5.6): answers carry {@code Server-Timing: app;dur=<ms>}, the time this backend spent on the
 * request until its answer is written. The performance tests limit that number, so they measure our code; the time
 * between GitHub's runners and Singapore is limited separately and more loosely. Other requests get no header (it
 * would tell anyone how long we work).
 */
@RestControllerAdvice
class ServerTiming implements ResponseBodyAdvice<Object> {

	static final String HEADER = "Server-Timing";

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
			Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
			ServerHttpResponse response) {
		if (TestFakes.active() && request instanceof ServletServerHttpRequest servlet) {
			HttpServletRequest raw = servlet.getServletRequest();
			if (raw.getAttribute(TestFakes.STARTED) instanceof Long started) {
				double ms = (System.nanoTime() - started) / 1_000_000.0;
				response.getHeaders().set(HEADER, String.format(java.util.Locale.ROOT, "app;dur=%.1f", ms));
			}
		}
		return body;
	}

}
