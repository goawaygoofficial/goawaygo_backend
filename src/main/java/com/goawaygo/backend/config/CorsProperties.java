package com.goawaygo.backend.config;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code goawaygo.cors.*}: browser origins (the web frontend) allowed to call the API. */
@Validated
@ConfigurationProperties("goawaygo.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {
}
