package com.goawaygo.backend.external;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code goawaygo.external.providers.<name>.*}: one entry per outside API (OpenTripMap, Unsplash, ...).
 *
 * <p>Keys come from Google Secret Manager as environment variables, e.g.
 * {@code GOAWAYGO_EXTERNAL_PROVIDERS_OPENTRIPMAP_APIKEY} (README.md, Deployment), never from this file.
 */
@Validated
@ConfigurationProperties("goawaygo.external")
public record ExternalApiProperties(Map<String, @Valid Provider> providers) {

	public ExternalApiProperties {
		providers = (providers != null) ? Map.copyOf(providers) : Map.of();
	}

	/**
	 * @param baseUrl e.g. {@code https://api.opentripmap.com/0.1}
	 * @param apiKey the provider's key, if it needs one
	 * @param connectTimeout time to open a connection
	 * @param readTimeout time to wait for the answer
	 * @param maxAttempts tries per request, including the first (temporary failures only)
	 * @param retryWait wait before the first retry; doubles each time
	 * @param dailyLimit most calls per UTC day; set below the provider's free limit (null: no daily limit)
	 * @param monthlyLimit most calls per UTC month; set below the provider's free limit (null: no monthly limit)
	 * @param cacheTtl how long a cached answer is fresh
	 */
	public record Provider(@NotBlank String baseUrl, String apiKey, @DefaultValue("3s") Duration connectTimeout,
			@DefaultValue("10s") Duration readTimeout, @DefaultValue("3") @Min(1) int maxAttempts,
			@DefaultValue("500ms") Duration retryWait, @Positive Integer dailyLimit, @Positive Integer monthlyLimit,
			@DefaultValue("24h") Duration cacheTtl) {

		/** Keys are never logged. */
		@Override
		public String toString() {
			return "Provider[baseUrl=" + this.baseUrl + ", dailyLimit=" + this.dailyLimit + ", monthlyLimit="
					+ this.monthlyLimit + "]";
		}

	}

}
