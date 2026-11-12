package com.goawaygo.backend.media;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.goawaygo.backend.common.error.ApiException;

/**
 * Stores and deletes files in Cloudflare R2 through its S3 API. Only two calls are needed, so requests are signed
 * here (AWS Signature Version 4, region {@code auto}) instead of pulling in the AWS SDK.
 */
@Component
public class R2Storage {

	private static final Logger log = LoggerFactory.getLogger(R2Storage.class);

	private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

	private static final String REGION = "auto";

	private final MediaProperties properties;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public R2Storage(MediaProperties properties) {
		this.properties = properties;
	}

	/** Stores a file that never changes (its name is random), cached by browsers and Cloudflare for a year. */
	public void put(String key, byte[] content, String contentType) {
		requireConfigured();
		HttpRequest.Builder request = signed("PUT", key, content)
			.header("Content-Type", contentType)
			.header("Cache-Control", "public, max-age=31536000, immutable")
			.PUT(HttpRequest.BodyPublishers.ofByteArray(content));
		send(request, "store");
	}

	/** Deletes a file; a missing one is fine. Failures are logged, not thrown (the database row is already gone). */
	public void delete(String key) {
		tryDelete(key);
	}

	/** Deletes a file (a missing one is fine). False if R2 could not be reached or refused; logged. */
	public boolean tryDelete(String key) {
		if (!this.properties.configured()) {
			return true;
		}
		try {
			send(signed("DELETE", key, new byte[0]).DELETE(), "delete");
			return true;
		}
		catch (RuntimeException ex) {
			log.error("Could not delete {} from R2: {}", key, ex.toString());
			return false;
		}
	}

	private void send(HttpRequest.Builder request, String action) {
		try {
			HttpResponse<String> response = this.http.send(request.timeout(Duration.ofSeconds(60)).build(),
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 300 && !(action.equals("delete") && response.statusCode() == 404)) {
				log.error("R2 {} failed: HTTP {} {}", action, response.statusCode(), response.body());
				throw unavailable();
			}
		}
		catch (IOException ex) {
			log.error("R2 {} failed: {}", action, ex.toString());
			throw unavailable();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
	}

	/** A request to {@code /<bucket>/<key>} with the Signature V4 headers. Keys are [a-z0-9/._-] only. */
	private HttpRequest.Builder signed(String method, String key, byte[] payload) {
		URI endpoint = URI.create(this.properties.endpoint().replaceAll("/+$", ""));
		String path = "/" + this.properties.bucket() + "/" + key;
		String host = endpoint.getHost() + ((endpoint.getPort() > 0) ? ":" + endpoint.getPort() : "");
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		String amzDate = AMZ_DATE.format(now);
		String date = amzDate.substring(0, 8);
		String payloadHash = hex(sha256(payload));
		String canonical = String.join("\n", method, path, "", "host:" + host, "x-amz-content-sha256:" + payloadHash,
				"x-amz-date:" + amzDate, "", "host;x-amz-content-sha256;x-amz-date", payloadHash);
		String scope = date + "/" + REGION + "/s3/aws4_request";
		String toSign = String.join("\n", "AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(utf8(canonical))));
		byte[] signingKey = hmac(hmac(hmac(hmac(utf8("AWS4" + this.properties.secretAccessKey()), date), REGION), "s3"),
				"aws4_request");
		String signature = hex(hmac(signingKey, toSign));
		return HttpRequest.newBuilder(URI.create(endpoint.getScheme() + "://" + host + path))
			.header("x-amz-content-sha256", payloadHash)
			.header("x-amz-date", amzDate)
			.header("Authorization", "AWS4-HMAC-SHA256 Credential=" + this.properties.accessKeyId() + "/" + scope
					+ ", SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + signature);
	}

	private void requireConfigured() {
		if (!this.properties.configured()) {
			log.error("Photo storage is not configured (goawaygo.media.access-key-id / secret-access-key)");
			throw unavailable();
		}
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable",
				"Photos can't be saved right now. Please try again later.");
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(utf8(data));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

}
