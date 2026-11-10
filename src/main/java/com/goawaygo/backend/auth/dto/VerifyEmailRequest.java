package com.goawaygo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(@NotBlank(message = "The verification link is missing its token.") String token) {
}
