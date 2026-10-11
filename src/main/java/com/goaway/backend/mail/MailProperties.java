package com.goaway.backend.mail;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code goaway.mail.*}.
 * @param from sender address, e.g. {@code Goaway <no-reply@example.com>}
 * @param async send in the background after the request (true everywhere except tests)
 */
@Validated
@ConfigurationProperties("goaway.mail")
public record MailProperties(@NotBlank String from, boolean async) {
}
