package com.goaway.backend.auth;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
		@Valid @NotNull RefreshCookie refreshCookie) {

	/** ES256 key pair, base64-encoded DER: PKCS#8 private key, X.509 public key. */
	public record Jwt(@NotBlank String privateKey, @NotBlank String publicKey) {
	}

	public record RefreshCookie(@NotBlank String name, @NotBlank String path, @NotBlank String sameSite, boolean secure) {
	}

}
