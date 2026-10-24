package com.goawaygo.backend.common.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes 401/403 responses from Spring Security in the same {@link ApiError} format as the rest of the API,
 * while keeping the standard {@code WWW-Authenticate: Bearer ...} header.
 */
@Component
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();

	private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();

	private final ObjectMapper objectMapper;

	public JsonSecurityErrorHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		this.bearerEntryPoint.commence(request, response, ex);
		write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "You need to sign in to do this.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		this.bearerAccessDenied.handle(request, response, ex);
		write(response, HttpStatus.FORBIDDEN, "forbidden", "You don't have permission to do this.");
	}

	private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		this.objectMapper.writeValue(response.getOutputStream(), new ApiError(status.value(), code, message));
	}

}
