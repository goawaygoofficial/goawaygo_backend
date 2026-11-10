package com.goawaygo.backend.logging;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTests {

	@Test
	void givesEachRequestAnIdInLogsAndResponseAndClearsItAfterwards() throws Exception {
		RequestIdFilter filter = new RequestIdFilter("goawaygo-staging");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
		request.addHeader("X-Cloud-Trace-Context", "0123456789abcdef0123456789abcdef/123;o=1");
		request.addHeader(RequestIdFilter.HEADER, "client-chosen-id");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> loggedId = new AtomicReference<>();
		AtomicReference<String> loggedTrace = new AtomicReference<>();

		filter.doFilter(request, response, new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				loggedId.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID));
				loggedTrace.set(MDC.get(RequestIdFilter.MDC_TRACE));
			}
		});

		String id = response.getHeader(RequestIdFilter.HEADER);
		assertThat(id).isNotBlank().isNotEqualTo("client-chosen-id").isEqualTo(loggedId.get());
		assertThat(loggedTrace.get()).isEqualTo("projects/goawaygo-staging/traces/0123456789abcdef0123456789abcdef");
		assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
		assertThat(MDC.get(RequestIdFilter.MDC_TRACE)).isNull();
	}

	@Test
	void ignoresTraceHeaderOutsideGoogleCloudOrWhenMalformed() {
		assertThat(new RequestIdFilter("").cloudTrace("0123456789abcdef0123456789abcdef/1")).isNull();
		RequestIdFilter onCloudRun = new RequestIdFilter("p");
		assertThat(onCloudRun.cloudTrace("not-a-trace")).isNull();
		assertThat(onCloudRun.cloudTrace(null)).isNull();
	}

}
