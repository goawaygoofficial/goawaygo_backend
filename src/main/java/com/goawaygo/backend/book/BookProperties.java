package com.goawaygo.backend.book;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.goawaygo.backend.ratelimit.RateLimitProperties;

/**
 * {@code goawaygo.book.*}: Book (4.3), flights, stays and car rentals through affiliate partners. Travellers book and
 * pay on the partner's site; we earn a commission. Travelpayouts itself is configured under {@code goawaygo.external}.
 *
 * @param marker our Travelpayouts partner id, in every affiliate link (not a secret)
 * @param projectId the Travelpayouts project ("trs") the links are counted under (not a secret)
 * @param maxFares most fares shown for a flight search (on the date asked, and on nearby dates)
 * @param pricesCacheTtl how long a flight price answer is reused (the prices are already a cache of recent searches)
 * @param linksCacheTtl how long a partner link made by Travelpayouts is reused
 * @param stays hotel partners, in the order shown
 * @param cars car rental partners, in the order shown
 * @param limits per-IP request limits
 */
@Validated
@ConfigurationProperties("goawaygo.book")
public record BookProperties(@NotBlank String marker, @NotBlank String projectId, @Positive int maxFares,
		@NotNull Duration pricesCacheTtl, @NotNull Duration linksCacheTtl, @NotNull List<@Valid Partner> stays,
		@NotNull List<@Valid Partner> cars, @NotNull @Valid Limits limits) {

	/**
	 * A partner opened with the traveller's search filled in. {@code url} may use {@code {place}}, {@code {checkin}},
	 * {@code {checkout}}, {@code {adults}}, {@code {rooms}}, {@code {airport}} (IATA code) and {@code {currency}}.
	 *
	 * @param id short name, also used in link statistics
	 * @param name shown on the card
	 * @param url the partner's search page
	 * @param fallbackUrl used when {@code url} needs {@code {airport}} and the search has none (empty: none)
	 * @param appAllowed the partner allows promotion in a mobile app (many brands allow websites only)
	 */
	public record Partner(@NotBlank String id, @NotBlank String name, @NotBlank String url, String fallbackUrl,
			boolean appAllowed) {
	}

	/**
	 * @param searchPerIp airport suggestions per IP
	 * @param flightsPerIp flight searches and price calendars per IP
	 * @param partnersPerIp stay and car searches per IP
	 */
	public record Limits(@NotNull @Valid RateLimitProperties.Limit searchPerIp,
			@NotNull @Valid RateLimitProperties.Limit flightsPerIp,
			@NotNull @Valid RateLimitProperties.Limit partnersPerIp) {
	}

}
