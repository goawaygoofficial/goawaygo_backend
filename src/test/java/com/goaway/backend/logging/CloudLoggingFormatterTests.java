package com.goaway.backend.logging;

import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import org.springframework.boot.json.JsonParserFactory;

import static org.assertj.core.api.Assertions.assertThat;

class CloudLoggingFormatterTests {

	private final CloudLoggingFormatter formatter = new CloudLoggingFormatter();

	private final Logger logger = new LoggerContext().getLogger("com.goaway.backend.Example");

	@Test
	void writesCloudLoggingFieldsWithRequestIdAndTrace() {
		LoggingEvent event = new LoggingEvent("fqcn", this.logger, Level.WARN, "Hello {}", null, new Object[] { "world" });
		event.setMDCPropertyMap(Map.of(RequestIdFilter.MDC_REQUEST_ID, "req-1", RequestIdFilter.MDC_TRACE,
				"projects/p/traces/abc"));

		String line = this.formatter.format(event);
		Map<String, Object> json = JsonParserFactory.getJsonParser().parseMap(line);

		assertThat(line).endsWith("\n").doesNotContain("\n{");
		assertThat(json).containsEntry("severity", "WARNING")
			.containsEntry("message", "Hello world")
			.containsEntry("logger", "com.goaway.backend.Example")
			.containsEntry("requestId", "req-1")
			.containsEntry("logging.googleapis.com/trace", "projects/p/traces/abc")
			.containsKey("time");
	}

	@Test
	void appendsTheStackTraceToErrorMessages() {
		LoggingEvent event = new LoggingEvent("fqcn", this.logger, Level.ERROR, "Unhandled exception",
				new IllegalStateException("boom"), null);
		event.setMDCPropertyMap(Map.of());

		Map<String, Object> json = JsonParserFactory.getJsonParser().parseMap(this.formatter.format(event));

		assertThat(json).containsEntry("severity", "ERROR").doesNotContainKey("requestId");
		assertThat((String) json.get("message")).startsWith("Unhandled exception\n")
			.contains("java.lang.IllegalStateException: boom")
			.contains("at com.goaway.backend.logging.CloudLoggingFormatterTests");
	}

	@Test
	void mapsLevelsToCloudLoggingSeverities() {
		assertThat(CloudLoggingFormatter.severity(Level.INFO)).isEqualTo("INFO");
		assertThat(CloudLoggingFormatter.severity(Level.DEBUG)).isEqualTo("DEBUG");
		assertThat(CloudLoggingFormatter.severity(Level.TRACE)).isEqualTo("DEBUG");
	}

}
