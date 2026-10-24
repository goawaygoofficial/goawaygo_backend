package com.goawaygo.backend.mail;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code goawaygo.mail.*}.
 * @param from sender address, e.g. {@code GoAwayGo <no-reply@example.com>}
 * @param async send from a background thread after the request (false by default: Cloud Run gives CPU only during
 *     requests, see EmailOutbox)
 */
@Validated
@ConfigurationProperties("goawaygo.mail")
public record MailProperties(@NotBlank String from, boolean async) {
}
