package com.goawaygo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
		@NotBlank(message = "The reset link is missing its token.") String token,
		// 72 is BCrypt's input limit.
		@NotBlank(message = "Password is required.")
		@Size(min = 8, max = 72, message = "Password must be 8 to 72 characters.") String newPassword) {
}
