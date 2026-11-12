package com.goawaygo.backend.notifications;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;

import com.goawaygo.backend.external.ExternalApis;

/**
 * An OAuth access token for the backend's own Google service account, from the Cloud Run metadata server (provider
 * {@code gcpmetadata}): no key file. Kept until a minute before it expires (they last about an hour).
 */
@Component
class GoogleAccessToken {

	private final ExternalApis apis;

	private String token;

	private Instant expires = Instant.EPOCH;

	GoogleAccessToken(ExternalApis apis) {
		this.apis = apis;
	}

	record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {
	}

	synchronized String get() {
		if (this.token != null && Instant.now().isBefore(this.expires)) {
			return this.token;
		}
		TokenResponse response = this.apis.client("gcpmetadata")
			.call((http, key) -> http.get()
				.uri("/computeMetadata/v1/instance/service-accounts/default/token")
				.header("Metadata-Flavor", "Google")
				.retrieve()
				.body(TokenResponse.class));
		if (response == null || response.accessToken() == null) {
			throw new IllegalStateException("No access token from the metadata server");
		}
		this.token = response.accessToken();
		this.expires = Instant.now().plusSeconds(Math.max(0, response.expiresIn() - 60));
		return this.token;
	}

}
