package com.goawaygo.backend.discover;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.goawaygo.backend.ratelimit.RateLimitProperties;

/**
 * {@code goawaygo.discover.*}: the Discover feature (destination pages, place search, ideas). The outside services
 * themselves (Gemini, Geoapify, Wikipedia, Wikidata, Unsplash) are configured under {@code goawaygo.external}.
 *
 * @param userAgent sent to Wikipedia, Wikidata and Wikimedia Commons, which ask every client to identify itself
 * @param cardModels AI models for the 9 destination cards, tried in order (the next one when one is busy,
 *     out of quota or failing)
 * @param ideaModels AI models for the ideas list, tried in order
 * @param aiDeadline give up on the AI after this long (whatever model it is on)
 * @param linkCheckTimeout how long a link from the AI may take to answer before it is dropped
 * @param ideas the ideas pool
 * @param limits per-IP request limits (each destination page costs one AI request)
 */
@Validated
@ConfigurationProperties("goawaygo.discover")
public record DiscoverProperties(@NotBlank String userAgent, @NotEmpty List<@Valid AiModel> cardModels,
		@NotEmpty List<@Valid AiModel> ideaModels, @NotNull Duration aiDeadline, @NotNull Duration linkCheckTimeout,
		@Valid @NotNull Ideas ideas, @Valid @NotNull Limits limits) {

	/**
	 * @param provider the {@code goawaygo.external.providers} entry to call (its key, limits and timeouts)
	 * @param model the Gemini model name, for example {@code gemini-3-flash-preview}
	 * @param thinkingLevel for models that "think" before answering: {@code minimal}, {@code low}, ... (empty:
	 *     model default)
	 */
	public record AiModel(@NotBlank String provider, @NotBlank String model, String thinkingLevel) {
	}

	/**
	 * @param poolSize how many places to ask the AI for at a time
	 * @param refreshAfter how long a pool of ideas is used before a new one is made
	 */
	public record Ideas(@Positive int poolSize, @NotNull Duration refreshAfter) {
	}

	public record Limits(@Valid @NotNull RateLimitProperties.Limit searchPerIp,
			@Valid @NotNull RateLimitProperties.Limit destinationPerIp,
			@Valid @NotNull RateLimitProperties.Limit photosPerIp, @Valid @NotNull RateLimitProperties.Limit ideasPerIp) {
	}

}
