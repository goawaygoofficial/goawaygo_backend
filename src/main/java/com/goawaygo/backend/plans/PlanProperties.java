package com.goawaygo.backend.plans;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.goawaygo.backend.discover.DiscoverProperties;
import com.goawaygo.backend.ratelimit.RateLimitProperties;

/**
 * {@code goawaygo.plans.*}: the AI trip planner (4.4). Gemini itself is configured under {@code goawaygo.external},
 * the same providers as Discover.
 *
 * @param models AI models, tried in order (the next when one is busy, out of quota or failing)
 * @param maxPerUser most saved plans per account
 * @param limits request limits
 */
@Validated
@ConfigurationProperties("goawaygo.plans")
public record PlanProperties(@NotEmpty List<DiscoverProperties.@Valid AiModel> models, @Positive int maxPerUser,
		@NotNull @Valid Limits limits) {

	/**
	 * @param createPerUser new plans per user (each costs an AI request)
	 * @param changesPerUser day changes per user (each costs an AI request)
	 * @param viewPerIp shared plan views per IP
	 */
	public record Limits(@NotNull @Valid RateLimitProperties.Limit createPerUser,
			@NotNull @Valid RateLimitProperties.Limit changesPerUser,
			@NotNull @Valid RateLimitProperties.Limit viewPerIp) {
	}

}
