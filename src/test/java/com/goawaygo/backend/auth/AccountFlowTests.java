package com.goawaygo.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;
import com.goawaygo.backend.mail.EmailMessage;
import com.goawaygo.backend.mail.RecordingEmailSender;

/**
 * Me (4.6): editing the account, changing the password and email (after "confirm it's you"), deleting the account
 * with 30 days to change your mind, and Recently viewed. R2 is a local stand-in that records deleted files.
 */
@SpringBootTest
class AccountFlowTests extends PostgresIntegrationTest {

	private static final String PASSWORD = "correct horse battery";

	private static final FakeR2 R2 = new FakeR2();

	@DynamicPropertySource
	static void outsideServices(DynamicPropertyRegistry registry) {
		registry.add("goawaygo.media.endpoint", R2::url);
		registry.add("goawaygo.media.access-key-id", () -> "test-key-id");
		registry.add("goawaygo.media.secret-access-key", () -> "test-secret");
		registry.add("goawaygo.media.public-base-url", () -> "https://media.test");
	}

	@MockitoBean
	private GoogleIdTokenVerifier google;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RecordingEmailSender emails;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		R2.deleted.clear();
		R2.failing = false;
	}

	@Test
	void nameCountryAndCurrencyCanBeChanged() throws Exception {
		Account asha = account("Asha");

		this.mvc.perform(get("/api/regions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.countries[?(@.code == 'IN')].currency").value("INR"))
			.andExpect(jsonPath("$.countries[?(@.code == 'IN')].name").value("India"))
			.andExpect(jsonPath("$.currencies[?(@.code == 'EUR')].name").value("Euro"));

		this.mvc.perform(asha.as(put("/api/users/me")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"  Asha Rao \",\"country\":\"in\",\"currency\":\"inr\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Asha Rao"))
			.andExpect(jsonPath("$.country").value("IN"))
			.andExpect(jsonPath("$.currency").value("INR"))
			.andExpect(jsonPath("$.has_password").value(true))
			.andExpect(jsonPath("$.google_linked").value(false));
		// The Wanderbook name is the account name.
		this.mvc.perform(get("/api/wanderbook/profiles/" + asha.publicId)).andExpect(jsonPath("$.name").value("Asha Rao"));

		this.mvc.perform(asha.as(put("/api/users/me")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Asha\",\"country\":\"ZZ\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_country"));
		this.mvc.perform(asha.as(put("/api/users/me")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Asha\",\"country\":\"\",\"currency\":null}"))
			.andExpect(jsonPath("$.country").value(Matchers.nullValue()));
	}

	@Test
	void changingThePasswordSignsOutEveryOtherDevice() throws Exception {
		Account asha = account("Asha");

		this.mvc.perform(asha.as(post("/api/auth/change-password")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"wrong one\",\"newPassword\":\"a brand new secret\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("wrong_password"));
		this.mvc.perform(asha.as(post("/api/auth/change-password")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"newPassword\":\"a brand new secret\"}"))
			.andExpect(jsonPath("$.error").value("confirm_identity"));

		String session = this.mvc.perform(asha.as(post("/api/auth/change-password")).header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\",\"newPassword\":\"a brand new secret\"}".formatted(PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andReturn().getResponse().getContentAsString();

		// The old session is gone; the new one works.
		refresh(asha.refreshToken).andExpect(status().isUnauthorized());
		refresh(JsonPath.read(session, "$.refreshToken")).andExpect(status().isOk());
		signIn(asha.email, PASSWORD).andExpect(status().isUnauthorized());
		signIn(asha.email, "a brand new secret").andExpect(status().isOk());
		assertThat(this.emails.latestTo(asha.email).orElseThrow().subject())
			.isEqualTo("Your GoAwayGo password was changed");
	}

	@Test
	void aNewEmailCountsOnceItsLinkIsOpened() throws Exception {
		Account asha = account("Asha");
		Account ben = account("Ben");
		String newEmail = "asha-new-" + UUID.randomUUID() + "@example.com";

		changeEmail(asha, ben.email).andExpect(status().isConflict());
		changeEmail(asha, newEmail)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(asha.email))
			.andExpect(jsonPath("$.pending_email").value(newEmail));

		EmailMessage confirm = this.emails.latestTo(newEmail).orElseThrow();
		assertThat(confirm.subject()).isEqualTo("Confirm your new email for GoAwayGo");
		assertThat(confirm.text()).contains("/confirm-email?token=");
		String token = RecordingEmailSender.tokenFrom(confirm);
		this.mvc.perform(post("/api/auth/confirm-email").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}")).andExpect(status().isNoContent());
		// Single use.
		this.mvc.perform(post("/api/auth/confirm-email").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}")).andExpect(status().isBadRequest());

		this.mvc.perform(asha.as(get("/api/users/me")))
			.andExpect(jsonPath("$.email").value(newEmail))
			.andExpect(jsonPath("$.email_verified").value(true))
			.andExpect(jsonPath("$.pending_email").value(Matchers.nullValue()));
		assertThat(this.emails.latestTo(asha.email).orElseThrow().text()).contains(newEmail);
		signIn(newEmail, PASSWORD).andExpect(status().isOk());
		signIn(asha.email, PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	void aGoogleAccountConfirmsWithGoogleAndCanSetAPassword() throws Exception {
		String email = "google-" + UUID.randomUUID() + "@example.com";
		String subject = "google-" + UUID.randomUUID();
		given(this.google.verify("their-token"))
			.willReturn(new GoogleIdTokenVerifier.GoogleIdentity(subject, email, true, "Gita", null));
		given(this.google.verify("someone-else"))
			.willReturn(new GoogleIdTokenVerifier.GoogleIdentity("other", "other@example.com", true, "Other", null));
		String session = this.mvc.perform(post("/api/auth/google-signin").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"their-token\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.has_password").value(false))
			.andExpect(jsonPath("$.user.google_linked").value(true))
			.andReturn().getResponse().getContentAsString();
		String access = JsonPath.read(session, "$.accessToken");

		this.mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"googleIdToken\":\"someone-else\",\"newPassword\":\"my first password\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("wrong_google_account"));
		this.mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"googleIdToken\":\"their-token\",\"newPassword\":\"my first password\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.has_password").value(true));
		signIn(email, "my first password").andExpect(status().isOk());
	}

	@Test
	void aDeletedAccountIsHiddenForThirtyDaysThenGoneWithItsPhotos() throws Exception {
		Account asha = account("Asha");
		Account ben = account("Ben");
		String postId = this.jdbc.queryForObject(
				"INSERT INTO wanderbook_posts (author_id, caption) VALUES (?, 'Sunset') RETURNING public_id",
				String.class, asha.id);
		this.jdbc.update("""
				INSERT INTO wanderbook_photos (public_id, owner_id, object_key, content_type, bytes, width, height)
				VALUES (?, ?, ?, 'image/jpeg', 10, 1, 1)""", "ph" + asha.id, asha.id, "p/ph" + asha.id + ".jpg");
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId + "/follow")))
			.andExpect(status().isOk());
		this.mvc.perform(asha.as(put("/api/notifications/devices")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"asha-phone\",\"platform\":\"android\"}")).andExpect(status().isNoContent());

		this.mvc.perform(asha.as(post("/api/auth/delete-account")).contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(jsonPath("$.error").value("confirm_identity"));
		this.mvc.perform(asha.as(post("/api/auth/delete-account")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleteOn").isNotEmpty())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("Max-Age=0")));
		assertThat(this.emails.latestTo(asha.email).orElseThrow().subject())
			.startsWith("Your GoAwayGo account will be deleted on ");

		// Signed out everywhere, no pushes, and hidden from others.
		refresh(asha.refreshToken).andExpect(status().isUnauthorized());
		this.mvc.perform(asha.as(get("/api/users/me"))).andExpect(status().isUnauthorized());
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM notification_devices WHERE user_id = ?",
				Integer.class, asha.id)).isZero();
		this.mvc.perform(ben.as(get("/api/wanderbook/profiles/" + asha.publicId))).andExpect(status().isNotFound());
		this.mvc.perform(ben.as(get("/api/wanderbook/posts/" + postId))).andExpect(status().isNotFound());
		this.mvc.perform(ben.as(get("/api/wanderbook/profiles/" + ben.publicId)))
			.andExpect(jsonPath("$.following").value(0));
		this.mvc.perform(get("/api/wanderbook/people").param("q", "Asha"))
			.andExpect(jsonPath("$[?(@.publicId == '" + asha.publicId + "')]").isEmpty());

		// Signing in again keeps the account.
		String session = signIn(asha.email, PASSWORD)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accountRestored").value(true))
			.andReturn().getResponse().getContentAsString();
		this.mvc.perform(ben.as(get("/api/wanderbook/profiles/" + asha.publicId))).andExpect(status().isOk());
		signIn(asha.email, PASSWORD).andExpect(jsonPath("$.accountRestored").doesNotExist());

		// Asked again; after 30 days the daily run deletes it, photo files first.
		Account again = new Account(JsonPath.read(session, "$.accessToken"), JsonPath.read(session, "$.refreshToken"),
				asha.id, asha.publicId, asha.email);
		this.mvc.perform(again.as(post("/api/auth/delete-account")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isOk());
		this.mvc.perform(post("/api/auth/purge-deleted-accounts")).andExpect(jsonPath("$.deleted").value(0));

		this.jdbc.update("UPDATE users SET deletion_requested_at = now() - interval '31 days' WHERE id = ?", asha.id);
		R2.failing = true;
		this.mvc.perform(post("/api/auth/purge-deleted-accounts")).andExpect(jsonPath("$.deleted").value(0));
		assertThat(exists(asha)).as("kept until its files are gone").isTrue();

		R2.failing = false;
		this.mvc.perform(post("/api/auth/purge-deleted-accounts")).andExpect(jsonPath("$.deleted").value(1));
		assertThat(R2.deleted).anyMatch(path -> path.endsWith("/p/ph" + asha.id + ".jpg"));
		assertThat(exists(asha)).isFalse();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wanderbook_posts WHERE public_id = ?", Integer.class,
				postId)).isZero();
		assertThat(this.emails.latestTo(asha.email).orElseThrow().subject())
			.isEqualTo("Your GoAwayGo account was deleted");
	}

	@Test
	void recentlyViewedListsPlacesAndPlans() throws Exception {
		Account asha = account("Asha");
		Account ben = account("Ben");
		String own = plan(asha, "Goa", false);
		String shared = plan(ben, "Jaipur", true);
		this.jdbc.update("""
				INSERT INTO search_history (user_id, place_id, name, type, country, country_code, lat, lon)
				VALUES (?, 'place-paris', 'Paris', 'city', 'France', 'FR', 48.85, 2.35)""", asha.id);

		this.mvc.perform(asha.as(get("/api/plans/" + own))).andExpect(status().isOk());
		this.mvc.perform(asha.as(get("/api/plans/shared/" + shared))).andExpect(status().isOk());
		// Signed out: nothing is recorded (and nothing breaks).
		this.mvc.perform(get("/api/plans/shared/" + shared)).andExpect(status().isOk());

		this.mvc.perform(asha.as(get("/api/me/recently-viewed")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.places[0].place.name").value("Paris"))
			.andExpect(jsonPath("$.places[0].viewedAt").isNotEmpty())
			.andExpect(jsonPath("$.plans.length()").value(2))
			.andExpect(jsonPath("$.plans[0].id").value(shared))
			.andExpect(jsonPath("$.plans[0].title").value("Jaipur in 3 days"))
			.andExpect(jsonPath("$.plans[0].mine").value(false))
			.andExpect(jsonPath("$.plans[1].id").value(own))
			.andExpect(jsonPath("$.plans[1].mine").value(true));

		// No longer shared: it drops out.
		this.jdbc.update("UPDATE trip_plans SET shared = false WHERE public_id = ?", shared);
		this.mvc.perform(asha.as(get("/api/me/recently-viewed"))).andExpect(jsonPath("$.plans.length()").value(1));

		this.mvc.perform(asha.as(delete("/api/me/recently-viewed"))).andExpect(status().isNoContent());
		this.mvc.perform(asha.as(get("/api/me/recently-viewed")))
			.andExpect(jsonPath("$.places.length()").value(0))
			.andExpect(jsonPath("$.plans.length()").value(0));
		this.mvc.perform(get("/api/me/recently-viewed")).andExpect(status().isUnauthorized());
	}

	private record Account(String token, String refreshToken, long id, String publicId, String email) {

		MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
			return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.token);
		}

	}

	private Account account(String name) throws Exception {
		String email = "me-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(name, email, PASSWORD)))
			.andExpect(status().isCreated());
		String session = signIn(email, PASSWORD).andReturn().getResponse().getContentAsString();
		Long id = this.jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		String publicId = this.jdbc.queryForObject("SELECT public_id FROM wanderbook_profiles WHERE user_id = ?",
				String.class, id);
		return new Account(JsonPath.read(session, "$.accessToken"), JsonPath.read(session, "$.refreshToken"), id,
				publicId, email);
	}

	private org.springframework.test.web.servlet.ResultActions signIn(String email, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/signin").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
	}

	private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
		return this.mvc.perform(post("/api/auth/refresh").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions changeEmail(Account account, String email)
			throws Exception {
		return this.mvc.perform(account.as(post("/api/auth/change-email")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\",\"newEmail\":\"%s\"}".formatted(PASSWORD, email)));
	}

	private boolean exists(Account account) {
		Integer count = this.jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, account.id);
		return count != null && count > 0;
	}

	private String plan(Account owner, String place, boolean shared) {
		return this.jdbc.queryForObject("""
				INSERT INTO trip_plans (user_id, place_name, country, lat, lon, start_date, days, travellers, pace,
				                        budget, currency, itinerary, model, shared)
				VALUES (?, ?, 'India', 15.3, 74.1, ?, 3, 'couple', 'balanced', 'mid', 'INR', ?::jsonb, 'test', ?)
				RETURNING public_id""", String.class, owner.id, place, LocalDate.now().plusDays(20),
				"{\"title\":\"%s in 3 days\",\"summary\":\"\",\"days\":[],\"tips\":[],\"stayPerNight\":2000}".formatted(place), shared);
	}

	/** R2: records deleted files; answers 500 while {@link #failing}. */
	static final class FakeR2 {

		final List<String> deleted = new CopyOnWriteArrayList<>();

		volatile boolean failing;

		private final HttpServer server;

		FakeR2() {
			try {
				this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			this.server.createContext("/", this::handle);
			this.server.setExecutor(Executors.newCachedThreadPool());
			this.server.start();
		}

		String url() {
			return "http://localhost:" + this.server.getAddress().getPort();
		}

		private void handle(HttpExchange exchange) throws IOException {
			exchange.getRequestBody().readAllBytes();
			if (this.failing) {
				exchange.sendResponseHeaders(500, -1);
			}
			else {
				if ("DELETE".equals(exchange.getRequestMethod())) {
					this.deleted.add(exchange.getRequestURI().getPath());
				}
				exchange.sendResponseHeaders(204, -1);
			}
			exchange.close();
		}

	}

}
