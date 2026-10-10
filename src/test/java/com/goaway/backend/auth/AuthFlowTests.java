package com.goaway.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goaway.backend.PostgresIntegrationTest;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** End-to-end tests of sign-up, sign-in, refresh, logout and /api/users/me against a real database. */
@SpringBootTest
class AuthFlowTests extends PostgresIntegrationTest {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ---- sign-up ----

	@Test
	void signupCreatesAnAccountWithNormalizedEmailAndNeverReturnsThePassword() throws Exception {
		String email = uniqueEmail();

		this.mvc.perform(signup("  Asha  ", "  " + email.toUpperCase() + " ", PASSWORD))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.name").value("Asha"))
			.andExpect(jsonPath("$.email_verified").value(false))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andExpect(jsonPath("$.password_hash").doesNotExist());

		String storedHash = this.jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class,
				email);
		assertThat(storedHash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
	}

	@Test
	void signupRejectsAnEmailThatIsAlreadyRegisteredIgnoringCase() throws Exception {
		String email = uniqueEmail();
		this.mvc.perform(signup("First", email, PASSWORD)).andExpect(status().isCreated());

		this.mvc.perform(signup("Second", email.toUpperCase(), PASSWORD))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("email_already_registered"))
			.andExpect(jsonPath("$.message").isNotEmpty());
	}

	@Test
	void signupValidatesInputAndReportsEachInvalidField() throws Exception {
		this.mvc.perform(signup("", "not-an-email", "short"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation_failed"))
			.andExpect(jsonPath("$.fieldErrors.name").exists())
			.andExpect(jsonPath("$.fieldErrors.email").exists())
			.andExpect(jsonPath("$.fieldErrors.password").exists());
	}

	@Test
	void malformedJsonGetsAClean400() throws Exception {
		this.mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("malformed_request"));
	}

	// ---- sign-in ----

	@Test
	void wrongPasswordAndUnknownEmailGetTheSameGenericError() throws Exception {
		String email = registeredUser();

		MvcResult wrongPassword = this.mvc.perform(signin(email, "wrong password!", true))
			.andExpect(status().isUnauthorized())
			.andReturn();
		MvcResult unknownEmail = this.mvc.perform(signin(uniqueEmail(), PASSWORD, true))
			.andExpect(status().isUnauthorized())
			.andReturn();

		assertThat(wrongPassword.getResponse().getContentAsString())
			.isEqualTo(unknownEmail.getResponse().getContentAsString())
			.contains("invalid_credentials");
	}

	@Test
	void mobileSigninReturnsBothTokensInTheBodyAndSetsNoCookie() throws Exception {
		String email = registeredUser();

		this.mvc.perform(signin(email, PASSWORD, true))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.email").value(email))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(20 * 60))
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	@Test
	void webSigninPutsTheRefreshTokenOnlyInAnHttpOnlyCookie() throws Exception {
		String email = registeredUser();

		MvcResult result = this.mvc.perform(signin(email, PASSWORD, false))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn();

		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).startsWith("goaway_refresh=")
			.contains("HttpOnly")
			.contains("Secure")
			.contains("SameSite=Strict")
			.contains("Path=/api/auth");
	}

	@Test
	void accessTokenIsAnEs256JwtValidFor20Minutes() throws Exception {
		String accessToken = JsonPath.read(signinBody(registeredUser()), "$.accessToken");
		String[] parts = accessToken.split("\\.");
		String header = new String(Base64.getUrlDecoder().decode(parts[0]));
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

		assertThat(header).contains("\"alg\":\"ES256\"");
		long issuedAt = ((Number) JsonPath.read(payload, "$.iat")).longValue();
		long expiresAt = ((Number) JsonPath.read(payload, "$.exp")).longValue();
		assertThat(expiresAt - issuedAt).isEqualTo(20 * 60);
		assertThat((String) JsonPath.read(payload, "$.iss")).isEqualTo("goaway-backend");
	}

	// ---- /api/users/me ----

	@Test
	void meReturnsTheSignedInUser() throws Exception {
		String email = registeredUser();
		String accessToken = JsonPath.read(signinBody(email), "$.accessToken");

		this.mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void meWithoutATokenIs401InTheStandardErrorFormat() throws Exception {
		this.mvc.perform(get("/api/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
			.andExpect(jsonPath("$.error").value("unauthorized"));
	}

	@Test
	void aTamperedAccessTokenIsRejected() throws Exception {
		String accessToken = JsonPath.read(signinBody(registeredUser()), "$.accessToken");
		String[] parts = accessToken.split("\\.");
		String forgedPayload = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(("{\"sub\":\"1\",\"iss\":\"goaway-backend\",\"aud\":[\"goaway-api\"],\"exp\":"
					+ Instant.now().plusSeconds(3600).getEpochSecond() + "}")
				.getBytes());
		String forged = parts[0] + "." + forgedPayload + "." + parts[2];

		this.mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
			.andExpect(status().isUnauthorized());
	}

	// ---- refresh / rotation / logout ----

	@Test
	void refreshRotatesTheTokenAndTheOldOneStopsWorking() throws Exception {
		String first = JsonPath.read(signinBody(registeredUser()), "$.refreshToken");

		String second = JsonPath.read(refreshMobile(first).andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.refreshToken");

		assertThat(second).isNotEqualTo(first);
		refreshMobile(first).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("invalid_refresh_token"));
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
		String stolen = JsonPath.read(signinBody(registeredUser()), "$.refreshToken");
		String legitimate = JsonPath.read(refreshMobile(stolen).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");

		// An attacker replays the old (already rotated) token...
		refreshMobile(stolen).andExpect(status().isUnauthorized());

		// ...so the legitimate user's current token is revoked too and they must sign in again.
		refreshMobile(legitimate).andExpect(status().isUnauthorized());
	}

	@Test
	void webRefreshUsesTheCookieAndRotatesIt() throws Exception {
		MvcResult signin = this.mvc.perform(signin(registeredUser(), PASSWORD, false)).andReturn();
		String cookieValue = cookieValue(signin.getResponse().getHeader(HttpHeaders.SET_COOKIE));

		MvcResult refreshed = this.mvc.perform(post("/api/auth/refresh").cookie(new Cookie("goaway_refresh", cookieValue)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn();

		assertThat(cookieValue(refreshed.getResponse().getHeader(HttpHeaders.SET_COOKIE))).isNotEqualTo(cookieValue);
	}

	@Test
	void logoutRevokesTheRefreshTokenAndClearsTheCookie() throws Exception {
		String refreshToken = JsonPath.read(signinBody(registeredUser()), "$.refreshToken");

		this.mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

		refreshMobile(refreshToken).andExpect(status().isUnauthorized());
	}

	@Test
	void refreshTokensAreStoredOnlyAsHashes() throws Exception {
		String email = registeredUser();
		String refreshToken = JsonPath.read(signinBody(email), "$.refreshToken");

		Integer rawMatches = this.jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
				Integer.class, refreshToken);
		Integer hashMatches = this.jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
				Integer.class, RefreshTokenService.hash(refreshToken));
		assertThat(rawMatches).isZero();
		assertThat(hashMatches).isOne();
	}

	// ---- helpers ----

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private String registeredUser() throws Exception {
		String email = uniqueEmail();
		this.mvc.perform(signup("Test User", email, PASSWORD)).andExpect(status().isCreated());
		return email;
	}

	private String signinBody(String email) throws Exception {
		return this.mvc.perform(signin(email, PASSWORD, true))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private org.springframework.test.web.servlet.ResultActions refreshMobile(String refreshToken) throws Exception {
		return this.mvc.perform(post("/api/auth/refresh").header(AuthController.CLIENT_TYPE_HEADER, "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	private static org.springframework.test.web.servlet.RequestBuilder signup(String name, String email,
			String password) {
		return post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(name, email, password));
	}

	private static org.springframework.test.web.servlet.RequestBuilder signin(String email, String password,
			boolean mobile) {
		var request = post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
		return mobile ? request.header(AuthController.CLIENT_TYPE_HEADER, "mobile") : request;
	}

	private static String cookieValue(String setCookieHeader) {
		return setCookieHeader.substring(setCookieHeader.indexOf('=') + 1, setCookieHeader.indexOf(';'));
	}

}
