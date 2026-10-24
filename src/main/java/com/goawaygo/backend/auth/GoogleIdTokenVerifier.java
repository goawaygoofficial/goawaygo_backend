package com.goawaygo.backend.auth;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.goawaygo.backend.common.error.ApiException;

/**
 * Verifies Google ID tokens: RS256 signature against Google's published keys (fetched and cached), issuer,
 * expiry, and that the audience is one of <em>our</em> client IDs, so tokens issued to other apps are rejected.
 *
 * <p>The decoder is deliberately not a {@code JwtDecoder} bean: that type is reserved for our own access tokens.
 */
@Component
public class GoogleIdTokenVerifier {

	private final NimbusJwtDecoder decoder;

	public GoogleIdTokenVerifier(AuthProperties properties) {
		AuthProperties.Google google = properties.google();
		this.decoder = NimbusJwtDecoder.withJwkSetUri(google.jwkSetUri())
			.jwsAlgorithm(SignatureAlgorithm.RS256)
			.build();
		this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
				new JwtClaimValidator<Object>(JwtClaimNames.ISS,
						issuer -> issuer != null && google.issuers().contains(issuer.toString())),
				audienceIsOneOf(google.clientIds()),
				new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank())));
	}

	/** @throws ApiException 401 {@code invalid_google_token} if the token is not a valid Google ID token for us */
	public GoogleIdentity verify(String idToken) {
		Jwt jwt;
		try {
			jwt = this.decoder.decode(idToken);
		}
		catch (JwtException ex) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_google_token",
					"Google sign-in failed. Please try again.");
		}
		return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"), isTrue(jwt.getClaim("email_verified")),
				jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
	}

	private static OAuth2TokenValidator<Jwt> audienceIsOneOf(List<String> clientIds) {
		OAuth2Error error = new OAuth2Error("invalid_token", "Token was not issued for this application", null);
		return jwt -> {
			List<String> audience = jwt.getAudience();
			boolean ours = audience != null && audience.stream().anyMatch(clientIds::contains);
			return ours ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
		};
	}

	/** Google sends {@code email_verified} as a JSON boolean; older tokens used the string "true". */
	private static boolean isTrue(Object claim) {
		return Boolean.TRUE.equals(claim) || "true".equals(claim);
	}

	/** The verified facts Google tells us about the user. */
	public record GoogleIdentity(String subject, String email, boolean emailVerified, String name, String pictureUrl) {
	}

}
