package com.goawaygo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The Google ID token obtained by the app or website from Google Sign-In. */
public record GoogleSigninRequest(@NotBlank(message = "Google ID token is required.") String idToken) {
}
