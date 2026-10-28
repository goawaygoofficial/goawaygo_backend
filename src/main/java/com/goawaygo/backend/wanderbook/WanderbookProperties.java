package com.goawaygo.backend.wanderbook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.goawaygo.backend.ratelimit.RateLimitProperties;

/**
 * {@code goawaygo.wanderbook.*}: Wanderbook. Profiles can be viewed signed out, so viewing is limited per IP
 * (against scraping); changes are limited per user.
 *
 * @param limits request limits
 */
@Validated
@ConfigurationProperties("goawaygo.wanderbook")
public record WanderbookProperties(@NotNull @Valid Limits limits) {

	/**
	 * @param viewPerIp profile and list views per IP
	 * @param searchPerIp people searches per IP
	 * @param changesPerUser follows, blocks, approvals and profile edits per signed-in user
	 */
	public record Limits(@NotNull @Valid RateLimitProperties.Limit viewPerIp,
			@NotNull @Valid RateLimitProperties.Limit searchPerIp,
			@NotNull @Valid RateLimitProperties.Limit changesPerUser) {
	}

}
