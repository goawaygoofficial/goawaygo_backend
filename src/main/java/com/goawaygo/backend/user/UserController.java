package com.goawaygo.backend.user;

import java.util.Locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;

/**
 * The signed-in user's own account (users can only ever read and change their own record here), and the countries
 * and currencies they can pick. Email, password and deleting the account are in {@code AuthController}; the photo is
 * the Wanderbook profile photo.
 */
@RestController
public class UserController {

	private final UserRepository users;

	public UserController(UserRepository users) {
		this.users = users;
	}

	/**
	 * @param country ISO 3166 code, or null/blank for none
	 * @param currency ISO 4217 code, or null/blank for none
	 */
	public record DetailsRequest(@NotBlank @Size(max = 100) String name, String country, String currency) {
	}

	@GetMapping("/api/users/me")
	@Transactional(readOnly = true)
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return UserResponse.from(current(jwt));
	}

	/** Name (also the Wanderbook name), country and currency. */
	@PutMapping("/api/users/me")
	@Transactional
	public UserResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DetailsRequest body) {
		String name = body.name().strip().replaceAll("\\p{Cntrl}", "");
		if (name.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_name", "Enter your name.");
		}
		String country = code(body.country());
		if (country != null && !Regions.isCountry(country)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_country", "Unknown country.");
		}
		String currency = code(body.currency());
		if (currency != null && !Regions.isCurrency(currency)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_currency", "Unknown currency.");
		}
		User user = current(jwt);
		user.updateDetails(name, country, currency);
		return UserResponse.from(user);
	}

	/** Every country (with its currency) and currency, names in English. The same for everyone: cached for a day. */
	@GetMapping("/api/regions")
	public ResponseEntity<Regions.All> regions() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePublic())
			.body(Regions.all());
	}

	private User current(Jwt jwt) {
		return this.users.findById(Long.parseLong(jwt.getSubject()))
			// Token still valid but the account is gone, or waiting to be deleted: treat as signed out.
			.filter(user -> user.getDeletionRequestedAt() == null)
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "You need to sign in to do this."));
	}

	private static String code(String value) {
		return (value == null || value.isBlank()) ? null : value.strip().toUpperCase(Locale.ROOT);
	}

}
