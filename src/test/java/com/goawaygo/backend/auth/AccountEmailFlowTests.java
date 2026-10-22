package com.goawaygo.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;
import com.goawaygo.backend.mail.EmailMessage;
import com.goawaygo.backend.mail.RecordingEmailSender;

/** Password reset and email verification, end to end: request → email → follow the link. */
@SpringBootTest
class AccountEmailFlowTests extends PostgresIntegrationTest {

	private static final String PASSWORD = "Original-pass-1";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private RecordingEmailSender emails;

	@Autowired
	private JdbcTemplate jdbc;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ---- email verification ----

	@Test
	void signupSendsAVerificationLinkToTheWebsite() throws Exception {
		String email = registeredUser();

		EmailMessage message = this.emails.latestTo(email).orElseThrow();
		assertThat(message.subject()).contains("Verify");
		assertThat(message.text()).contains("http://localhost:5173/verify-email?token=");
		assertThat(message.html()).contains("/verify-email?token=");
	}

	@Test
	void theVerificationLinkVerifiesTheEmailOnce() throws Exception {
		String email = registeredUser();
		String token = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());

		post("/api/auth/verify-email", "{\"token\":\"" + token + "\"}").andExpect(status().isNoContent());
		assertThat(emailVerified(email)).isTrue();

		post("/api/auth/verify-email", "{\"token\":\"" + token + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_or_expired_link"));
	}

	@Test
	void resendVerificationNeedsSignInAndSendsANewLinkThatReplacesTheOld() throws Exception {
		String email = registeredUser();
		String first = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());

		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/resend-verification"))
			.andExpect(status().isUnauthorized());

		this.mvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/resend-verification")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(email, PASSWORD)))
			.andExpect(status().isAccepted());
		String second = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());

		assertThat(second).isNotEqualTo(first);
		post("/api/auth/verify-email", "{\"token\":\"" + first + "\"}").andExpect(status().isBadRequest());
		post("/api/auth/verify-email", "{\"token\":\"" + second + "\"}").andExpect(status().isNoContent());
	}

	@Test
	void resendVerificationForAnAlreadyVerifiedEmailIsAConflict() throws Exception {
		String email = registeredUser();
		post("/api/auth/verify-email",
				"{\"token\":\"" + RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow()) + "\"}");

		this.mvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/resend-verification")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(email, PASSWORD)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("email_already_verified"));
	}

	// ---- password reset ----

	@Test
	void forgotPasswordAnswersTheSameWhetherOrNotTheAccountExists() throws Exception {
		String existing = registeredUser();
		String unknown = uniqueEmail();

		String forExisting = post("/api/auth/forgot-password", "{\"email\":\"" + existing + "\"}")
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String forUnknown = post("/api/auth/forgot-password", "{\"email\":\"" + unknown + "\"}")
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(forExisting).isEqualTo(forUnknown);
		assertThat(this.emails.latestTo(existing).orElseThrow().subject()).contains("Reset");
		assertThat(this.emails.sentTo(unknown)).isEmpty();
	}

	@Test
	void resetPasswordChangesThePasswordSignsOutEverywhereAndVerifiesTheEmail() throws Exception {
		String email = registeredUser();
		String oldRefreshToken = JsonPath.read(signin(email, PASSWORD).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");
		post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}");
		String token = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());

		post("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"Brand-new-pass-2\"}")
			.andExpect(status().isNoContent());

		signin(email, PASSWORD).andExpect(status().isUnauthorized());
		signin(email, "Brand-new-pass-2").andExpect(status().isOk());
		post("/api/auth/refresh", "{\"refreshToken\":\"" + oldRefreshToken + "\"}").andExpect(status().isUnauthorized());
		assertThat(emailVerified(email)).isTrue();
	}

	@Test
	void aResetLinkWorksOnlyOnce() throws Exception {
		String email = registeredUser();
		post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}");
		String token = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());

		post("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"First-reset-pass-3\"}")
			.andExpect(status().isNoContent());
		post("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"Second-reset-pass-4\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_or_expired_link"));
	}

	@Test
	void requestingANewResetLinkInvalidatesTheOlderOne() throws Exception {
		String email = registeredUser();
		post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}");
		String older = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());
		post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}");

		post("/api/auth/reset-password", "{\"token\":\"" + older + "\",\"newPassword\":\"Another-pass-5\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void anExpiredResetLinkIsRejected() throws Exception {
		String email = registeredUser();
		post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}");
		String token = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());
		this.jdbc.update("UPDATE user_action_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
				RefreshTokenService.hash(token));

		post("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"Too-late-pass-6\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void aVerificationLinkCannotBeUsedToResetThePassword() throws Exception {
		String email = registeredUser();
		String verificationToken = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());

		post("/api/auth/reset-password", "{\"token\":\"" + verificationToken + "\",\"newPassword\":\"Sneaky-pass-7\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void resetPasswordValidatesTheNewPassword() throws Exception {
		post("/api/auth/reset-password", "{\"token\":\"anything\",\"newPassword\":\"short\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.newPassword").exists());
	}

	@Test
	void onlyHashesOfEmailedTokensAreStored() throws Exception {
		String email = registeredUser();
		String token = RecordingEmailSender.tokenFrom(this.emails.latestTo(email).orElseThrow());

		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM user_action_tokens WHERE token_hash = ?",
				Integer.class, token)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM user_action_tokens WHERE token_hash = ?",
				Integer.class, RefreshTokenService.hash(token))).isOne();
	}

	// ---- helpers ----

	private String registeredUser() throws Exception {
		String email = uniqueEmail();
		post("/api/auth/signup", "{\"name\":\"Mail Tester\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().isCreated());
		return email;
	}

	private ResultActions signin(String email, String password) throws Exception {
		return this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/signin")
			.header(AuthController.CLIENT_TYPE_HEADER, "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private String accessToken(String email, String password) throws Exception {
		return JsonPath.read(signin(email, password).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	private ResultActions post(String path, String json) throws Exception {
		return this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.header(AuthController.CLIENT_TYPE_HEADER, "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private boolean emailVerified(String email) {
		return Boolean.TRUE
			.equals(this.jdbc.queryForObject("SELECT email_verified FROM users WHERE email = ?", Boolean.class, email));
	}

	private static String uniqueEmail() {
		return "mail-" + UUID.randomUUID() + "@example.com";
	}

}
