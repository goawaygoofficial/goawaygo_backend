package com.goawaygo.backend.auth;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.goawaygo.backend.common.error.ApiException;

/**
 * Google's authorization-code flow, used by the website's custom Google button: exchanges the one-time code the
 * browser received for an ID token, using the Web client's secret (which never leaves the backend). The ID token
 * is then verified like any other by {@link GoogleIdTokenVerifier}.
 */
@Component
public class GoogleAuthorizationCodeExchanger {

	private static final Logger log = LoggerFactory.getLogger(GoogleAuthorizationCodeExchanger.class);

	/** What Google's JavaScript code client uses as redirect URI in popup mode. */
	private static final String POPUP_REDIRECT_URI = "postmessage";

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
	};

	private final AuthProperties.Google google;

	private final RestClient http;

	public GoogleAuthorizationCodeExchanger(AuthProperties properties) {
		this.google = properties.google();
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		this.http = RestClient.builder().requestFactory(requestFactory).build();
	}

	/**
	 * @return Google's ID token for the user who approved the code
	 * @throws ApiException 503 if not configured, 401 if Google rejects the code, 503 if Google is unreachable
	 */
	public String exchangeForIdToken(String code) {
		if (!this.google.codeFlowEnabled()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_sign_in_unavailable",
					"Google sign-in is not available right now.");
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("code", code);
		form.add("client_id", this.google.webClientId());
		form.add("client_secret", this.google.clientSecret());
		form.add("redirect_uri", POPUP_REDIRECT_URI);
		form.add("grant_type", "authorization_code");

		Map<String, Object> response;
		try {
			response = this.http.post()
				.uri(this.google.tokenUri())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(JSON_OBJECT);
		}
		catch (HttpClientErrorException ex) {
			// Expired, reused or forged code. Never log the code or the secret.
			log.info("Google rejected an authorization code: HTTP {}", ex.getStatusCode().value());
			throw invalidCode();
		}
		catch (RestClientException ex) {
			log.warn("Could not reach Google's token endpoint: {}", ex.getMessage());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_unreachable",
					"Couldn't reach Google. Please try again.");
		}
		Object idToken = (response == null) ? null : response.get("id_token");
		if (!(idToken instanceof String token) || token.isBlank()) {
			throw invalidCode();
		}
		return token;
	}

	private static ApiException invalidCode() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_google_code", "Google sign-in failed. Please try again.");
	}

}
