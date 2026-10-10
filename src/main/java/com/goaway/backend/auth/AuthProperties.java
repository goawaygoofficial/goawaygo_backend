package com.goaway.backend.auth;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code goaway.auth.*} settings. Validated at startup so a missing key fails fast. */
@Validated
@ConfigurationProperties("goaway.auth")
public record AuthProperties(
		@NotBlank String issuer,
		@NotBlank String audience,
		@NotNull Duration accessTokenTtl,
		@NotNull Duration refreshTokenTtl,
		@Valid @NotNull Jwt jwt,
		@Valid @NotNull RefreshCookie refreshCookie,
		@Valid @NotNull Google google) {

	/** ES256 key pair, base64-encoded DER: PKCS#8 private key, X.509 public key. */
	public record Jwt(@NotBlank String privateKey, @NotBlank String publicKey) {
	}

	public record RefreshCookie(@NotBlank String name, @NotBlank String path, @NotBlank String sameSite, boolean secure) {
	}

	/**
	 * Google Sign-In.
	 * @param clientIds our OAuth client IDs; an ID token is accepted only if its audience is one of them
	 * @param jwkSetUri where Google publishes the keys that sign ID tokens
	 * @param issuers accepted {@code iss} values
	 */
	public record Google(@NotEmpty List<@NotBlank String> clientIds, @NotBlank String jwkSetUri,
			@NotEmpty List<@NotBlank String> issuers) {
	}

}
