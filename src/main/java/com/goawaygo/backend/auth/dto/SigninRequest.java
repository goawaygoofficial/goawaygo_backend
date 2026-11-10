package com.goawaygo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SigninRequest(
		@NotBlank(message = "Email is required.") String email,
		@NotBlank(message = "Password is required.") String password) {

	/** Trim the email (not the password): mobile keyboards often add a trailing space after autocomplete. */
	public SigninRequest {
		email = email == null ? null : email.trim();
	}

}
