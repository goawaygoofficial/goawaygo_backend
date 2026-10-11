package com.goaway.backend.ratelimit;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code goaway.rate-limit.*}: how many requests are allowed per period, per client IP or per email. */
@Validated
@ConfigurationProperties("goaway.rate-limit")
public record RateLimitProperties(
		boolean enabled,
		@Valid @NotNull Limit signinPerIp,
		@Valid @NotNull Limit signinPerEmail,
		@Valid @NotNull Limit signupPerIp,
		@Valid @NotNull Limit googleSigninPerIp,
		@Valid @NotNull Limit refreshPerIp,
		@Valid @NotNull Limit forgotPasswordPerIp,
		@Valid @NotNull Limit forgotPasswordPerEmail) {

	/** At most {@code capacity} requests per {@code period}, refilled gradually. */
	public record Limit(@Positive int capacity, @NotNull Duration period) {
	}

}
