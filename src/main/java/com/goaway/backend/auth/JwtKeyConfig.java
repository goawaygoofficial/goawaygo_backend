package com.goaway.backend.auth;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs and verifies access tokens with an ES256 (P-256) key pair. Only the backend holds the private key;
 * the public key could later be shared with other services so they can verify tokens without it.
 */
@Configuration
class JwtKeyConfig {

	private static final String KEY_ID = "goaway-access-1";

	@Bean
	ECKey accessTokenSigningKey(AuthProperties properties) {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance("EC");
			var privateKey = (ECPrivateKey) keyFactory
				.generatePrivate(new PKCS8EncodedKeySpec(decode(properties.jwt().privateKey(), "private")));
			var publicKey = (ECPublicKey) keyFactory
				.generatePublic(new X509EncodedKeySpec(decode(properties.jwt().publicKey(), "public")));
			return new ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey).keyID(KEY_ID).build();
		}
		catch (GeneralSecurityException | ClassCastException ex) {
			throw new IllegalStateException(
					"goaway.auth.jwt keys are not a valid ES256 (P-256) key pair; see .env.example", ex);
		}
	}

	@Bean
	JwtEncoder jwtEncoder(ECKey accessTokenSigningKey) {
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(accessTokenSigningKey)));
	}

	@Bean
	JwtDecoder jwtDecoder(ECKey accessTokenSigningKey, AuthProperties properties) {
		DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
				new ImmutableJWKSet<>(new JWKSet(accessTokenSigningKey.toPublicJWK()))));
		// Claims are checked by Spring's validators below, not by Nimbus.
		processor.setJWTClaimsSetVerifier((claims, context) -> {
		});

		NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(properties.issuer()),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
						audience -> audience != null && audience.contains(properties.audience()))));
		return decoder;
	}

	private static byte[] decode(String base64, String which) {
		try {
			return Base64.getDecoder().decode(base64.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("goaway.auth.jwt." + which + "-key is not valid base64", ex);
		}
	}

}
