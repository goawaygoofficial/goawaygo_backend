package com.goaway.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goaway.backend.PostgresIntegrationTest;

/**
 * Google Sign-In against a local stand-in for Google's key endpoint, so the real verification path runs:
 * key download, RS256 signature, issuer, audience and expiry checks.
 */
@SpringBootTest
class GoogleSignInTests extends PostgresIntegrationTest {

	private static final String WEB_CLIENT_ID = "test-web-client.apps.googleusercontent.com";

	private static final String IOS_CLIENT_ID = "test-ios-client.apps.googleusercontent.com";

	private static final String GOOGLE_ISSUER = "https://accounts.google.com";

	/** Plays Google: signs ID tokens and publishes the public key. */
	private static final RSAKey GOOGLE_KEY = generateRsaKey("google-test-key");

	/** A key Google never published: tokens signed with it must be rejected. */
	private static final RSAKey ATTACKER_KEY = generateRsaKey("google-test-key");

	private static final HttpServer GOOGLE_CERTS = startCertsServer();

	@DynamicPropertySource
	static void googleProperties(DynamicPropertyRegistry registry) {
		registry.add("goaway.auth.google.jwk-set-uri",
				() -> "http://localhost:" + GOOGLE_CERTS.getAddress().getPort() + "/oauth2/v3/certs");
		registry.add("goaway.auth.google.client-ids", () -> WEB_CLIENT_ID + "," + IOS_CLIENT_ID);
	}

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@Test
	void firstGoogleSignInCreatesAVerifiedPasswordlessAccount() throws Exception {
		String email = uniqueEmail();
		String subject = uniqueSubject();

		googleSignin(token(subject, email, true, WEB_CLIENT_ID)).andExpect(status().isOk())
			.andExpect(jsonPath("$.user.email").value(email))
			.andExpect(jsonPath("$.user.name").value("Asha Rao"))
			.andExpect(jsonPath("$.user.email_verified").value(true))
			.andExpect(jsonPath("$.user.profile_picture").value("https://example.com/asha.png"))
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty());

		Map<String, Object> row = this.jdbc.queryForMap("SELECT google_subject, password_hash FROM users WHERE email = ?",
				email);
		assertThat(row.get("google_subject")).isEqualTo(subject);
		assertThat(row.get("password_hash")).isNull();
	}

	@Test
	void signingInAgainWithTheSameGoogleAccountReturnsTheSameUser() throws Exception {
		String email = uniqueEmail();
		String subject = uniqueSubject();

		long firstId = userId(googleSignin(token(subject, email, true, WEB_CLIENT_ID)));
		long secondId = userId(googleSignin(token(subject, email, true, IOS_CLIENT_ID)));

		assertThat(secondId).isEqualTo(firstId);
	}

	@Test
	void googleSignInWithAnEmailFromAnUnverifiedPasswordAccountTakesItOverSafely() throws Exception {
		String email = uniqueEmail();
		// Someone (maybe not the owner of the email) registered it with a password and signed in.
		this.mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Squatter\",\"email\":\"" + email + "\",\"password\":\"squatter-password\"}"))
			.andExpect(status().isCreated());
		String squatterRefresh = JsonPath.read(this.mvc.perform(post("/api/auth/signin")
			.header(AuthController.CLIENT_TYPE_HEADER, "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"squatter-password\"}"))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.refreshToken");

		// The real owner signs in with Google, which has verified the email.
		googleSignin(token(uniqueSubject(), email, true, WEB_CLIENT_ID)).andExpect(status().isOk())
			.andExpect(jsonPath("$.user.email_verified").value(true));

		// The squatter's password and sessions no longer work.
		this.mvc.perform(post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"squatter-password\"}"))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(post("/api/auth/refresh").header(AuthController.CLIENT_TYPE_HEADER, "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + squatterRefresh + "\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void anEmailAlreadyLinkedToAnotherGoogleAccountIsNotSwitched() throws Exception {
		String email = uniqueEmail();
		googleSignin(token(uniqueSubject(), email, true, WEB_CLIENT_ID)).andExpect(status().isOk());

		googleSignin(token(uniqueSubject(), email, true, WEB_CLIENT_ID)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("email_linked_to_other_google_account"));
	}

	@Test
	void unverifiedGoogleEmailIsRefused() throws Exception {
		googleSignin(token(uniqueSubject(), uniqueEmail(), false, WEB_CLIENT_ID)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("google_email_not_verified"));
	}

	@Test
	void tokenIssuedForAnotherAppIsRejected() throws Exception {
		googleSignin(token(uniqueSubject(), uniqueEmail(), true, "someone-elses-app.apps.googleusercontent.com"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("invalid_google_token"));
	}

	@Test
	void tokenSignedWithAKeyGoogleNeverPublishedIsRejected() throws Exception {
		String forged = sign(ATTACKER_KEY, claims(uniqueSubject(), uniqueEmail(), true, WEB_CLIENT_ID, GOOGLE_ISSUER,
				Instant.now().plusSeconds(3600)));

		googleSignin(forged).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("invalid_google_token"));
	}

	@Test
	void expiredTokenIsRejected() throws Exception {
		String expired = sign(GOOGLE_KEY, claims(uniqueSubject(), uniqueEmail(), true, WEB_CLIENT_ID, GOOGLE_ISSUER,
				Instant.now().minusSeconds(600)));

		googleSignin(expired).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenFromAnotherIssuerIsRejected() throws Exception {
		String wrongIssuer = sign(GOOGLE_KEY, claims(uniqueSubject(), uniqueEmail(), true, WEB_CLIENT_ID,
				"https://evil.example.com", Instant.now().plusSeconds(3600)));

		googleSignin(wrongIssuer).andExpect(status().isUnauthorized());
	}

	@Test
	void garbageTokenAndMissingTokenAreHandledCleanly() throws Exception {
		googleSignin("not-a-jwt").andExpect(status().isUnauthorized());
		this.mvc.perform(post("/api/auth/google-signin").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.idToken").exists());
	}

	// ---- helpers ----

	private ResultActions googleSignin(String idToken) throws Exception {
		return this.mvc.perform(post("/api/auth/google-signin").header(AuthController.CLIENT_TYPE_HEADER, "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"idToken\":\"" + idToken + "\"}"));
	}

	private static long userId(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.user.id")).longValue();
	}

	private static String token(String subject, String email, boolean emailVerified, String audience) {
		return sign(GOOGLE_KEY,
				claims(subject, email, emailVerified, audience, GOOGLE_ISSUER, Instant.now().plusSeconds(3600)));
	}

	private static JWTClaimsSet claims(String subject, String email, boolean emailVerified, String audience,
			String issuer, Instant expiresAt) {
		return new JWTClaimsSet.Builder().issuer(issuer)
			.audience(List.of(audience))
			.subject(subject)
			.issueTime(Date.from(expiresAt.minusSeconds(3600)))
			.expirationTime(Date.from(expiresAt))
			.claim("email", email)
			.claim("email_verified", emailVerified)
			.claim("name", "Asha Rao")
			.claim("picture", "https://example.com/asha.png")
			.build();
	}

	private static String sign(RSAKey key, JWTClaimsSet claims) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
					claims);
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String uniqueEmail() {
		return "google-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniqueSubject() {
		return "1" + Math.abs(UUID.randomUUID().getMostSignificantBits());
	}

	private static RSAKey generateRsaKey(String keyId) {
		try {
			return new RSAKeyGenerator(2048).keyID(keyId).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static HttpServer startCertsServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			byte[] body = new JWKSet(GOOGLE_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
			server.createContext("/oauth2/v3/certs", exchange -> {
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
