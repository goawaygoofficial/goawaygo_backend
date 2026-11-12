package com.goawaygo.backend.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived, signed access tokens (JWT). They are not stored; they simply expire. */
@Service
public class AccessTokenService {

	private final JwtEncoder encoder;

	private final AuthProperties properties;

	private final Clock clock;

	public AccessTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public IssuedToken issue(long userId) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(this.properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(this.properties.issuer())
			.audience(List.of(this.properties.audience()))
			.subject(Long.toString(userId))
			.issuedAt(now)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString())
			.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).build();
		String token = this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, now, expiresAt);
	}

	public record IssuedToken(String value, Instant issuedAt, Instant expiresAt) {
	}

}
