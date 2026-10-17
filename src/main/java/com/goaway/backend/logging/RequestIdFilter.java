package com.goaway.backend.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.sentry.Sentry;
import org.slf4j.MDC;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID: put in the log context (each log line of the request carries it), sent back as the
 * {@code X-Request-Id} response header (a user or tester can quote it), and tagged on Sentry errors. The ID is always
 * generated here, never taken from the client.
 *
 * <p>On Cloud Run it also links the log lines to Google's trace of the request ({@code X-Cloud-Trace-Context}), so
 * the Logs Explorer shows them together. That header only groups logs; nothing else depends on it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after Sentry's own filter, which opens the per-request scope
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	static final String MDC_REQUEST_ID = "requestId";

	static final String MDC_TRACE = "trace";

	private static final Pattern CLOUD_TRACE = Pattern.compile("^([0-9a-f]{32})(/.*)?$");

	private final String gcpProject;

	public RequestIdFilter(@Value("${GOOGLE_CLOUD_PROJECT:}") String gcpProject) {
		this.gcpProject = gcpProject;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		MDC.put(MDC_REQUEST_ID, requestId);
		String trace = cloudTrace(request.getHeader("X-Cloud-Trace-Context"));
		if (trace != null) {
			MDC.put(MDC_TRACE, trace);
		}
		Sentry.setTag("request_id", requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_REQUEST_ID);
			MDC.remove(MDC_TRACE);
		}
	}

	/** {@code projects/<project>/traces/<id>} from Cloud Run's trace header, or null outside Google Cloud. */
	String cloudTrace(String header) {
		if (header == null || this.gcpProject.isBlank()) {
			return null;
		}
		Matcher matcher = CLOUD_TRACE.matcher(header.trim().toLowerCase());
		return matcher.matches() ? "projects/" + this.gcpProject + "/traces/" + matcher.group(1) : null;
	}

}
