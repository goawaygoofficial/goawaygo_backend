package com.goawaygo.backend.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * Writes each log line as the JSON object Google Cloud Logging reads from Cloud Run's output: {@code severity},
 * {@code message}, {@code time}, the logger and thread, the request ID, and the trace (so all lines of one request
 * group together in the Logs Explorer). Stack traces are appended to the message, which is how Google Error
 * Reporting recognises an error.
 *
 * <p>Enabled with {@code logging.structured.format.console=com.goawaygo.backend.logging.CloudLoggingFormatter}
 * (deployed environments only; local logs stay human-readable).
 */
public class CloudLoggingFormatter implements StructuredLogFormatter<ILoggingEvent> {

	private static final JsonWriter<Map<String, Object>> JSON = JsonWriter.standard();

	@Override
	public String format(ILoggingEvent event) {
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("severity", severity(event.getLevel()));
		line.put("message", messageWithStackTrace(event));
		line.put("time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
		line.put("logger", event.getLoggerName());
		line.put("thread", event.getThreadName());
		Map<String, String> mdc = event.getMDCPropertyMap();
		String requestId = mdc.get(RequestIdFilter.MDC_REQUEST_ID);
		if (requestId != null) {
			line.put("requestId", requestId);
		}
		String trace = mdc.get(RequestIdFilter.MDC_TRACE);
		if (trace != null) {
			line.put("logging.googleapis.com/trace", trace);
		}
		return JSON.writeToString(line) + "\n";
	}

	static String severity(Level level) {
		return switch (level.toInt()) {
			case Level.ERROR_INT -> "ERROR";
			case Level.WARN_INT -> "WARNING";
			case Level.INFO_INT -> "INFO";
			default -> "DEBUG";
		};
	}

	private static String messageWithStackTrace(ILoggingEvent event) {
		IThrowableProxy throwable = event.getThrowableProxy();
		String message = event.getFormattedMessage();
		return (throwable == null) ? message : message + "\n" + ThrowableProxyUtil.asString(throwable);
	}

}
