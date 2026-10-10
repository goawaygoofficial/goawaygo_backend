package com.goaway.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "Name is required.") @Size(max = 100, message = "Name must be at most 100 characters.") String name,
		@NotBlank(message = "Email is required.") @Email(message = "Enter a valid email address.")
		@Size(max = 320, message = "Email is too long.") String email,
		// 72 is BCrypt's input limit.
		@NotBlank(message = "Password is required.")
		@Size(min = 8, max = 72, message = "Password must be 8 to 72 characters.") String password) {

	/** Trim before validation: mobile keyboards often add a trailing space after autocomplete. */
	public SignupRequest {
		name = name == null ? null : name.trim();
		email = email == null ? null : email.trim();
	}

}
