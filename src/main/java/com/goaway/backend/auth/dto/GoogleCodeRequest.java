package com.goaway.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The one-time authorization code from Google's code client (website custom button, popup mode). */
public record GoogleCodeRequest(@NotBlank(message = "Google authorization code is required.") String code) {
}
