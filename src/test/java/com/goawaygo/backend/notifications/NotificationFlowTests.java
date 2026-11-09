package com.goawaygo.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;

/**
 * Notifications end to end: Wanderbook activity and trip reminders in the bell, pushes through a fake Firebase Cloud
 * Messaging (and metadata server for its token), the switches, and announcements.
 */
@SpringBootTest
class NotificationFlowTests extends PostgresIntegrationTest {

	private static final FakeGoogle FAKE = new FakeGoogle();

	@DynamicPropertySource
	static void outsideServices(DynamicPropertyRegistry registry) {
		for (String provider : List.of("fcm", "gcpmetadata", "openai")) {
			registry.add("goawaygo.external.providers." + provider + ".base-url", FAKE::url);
		}
		registry.add("goawaygo.external.providers.openai.api-key", () -> "test-key");
		registry.add("goawaygo.notifications.fcm-project", () -> "test-project");
	}

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NotificationService notifications;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		FAKE.pushes.clear();
	}

	@Test
	void followsRequestsAndApprovalsShowInTheBell() throws Exception {
		Traveller asha = traveller("Asha");
		Traveller ben = traveller("Ben");
		Traveller cara = traveller("Cara");

		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId + "/follow")))
			.andExpect(status().isOk());
		this.mvc.perform(asha.as(get("/api/notifications")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unread").value(1))
			.andExpect(jsonPath("$.items[0].type").value("follow"))
			.andExpect(jsonPath("$.items[0].message").value("Ben started following you."))
			.andExpect(jsonPath("$.items[0].actor.publicId").value(ben.publicId))
			.andExpect(jsonPath("$.items[0].read").value(false));

		// Unfollowing takes the unread notification back.
		this.mvc.perform(ben.as(delete("/api/wanderbook/profiles/" + asha.publicId + "/follow")))
			.andExpect(status().isOk());
		this.mvc.perform(asha.as(get("/api/notifications/unread"))).andExpect(jsonPath("$.unread").value(0));

		// A private profile gets a request; approving it tells the requester and clears the request.
		this.mvc.perform(cara.as(put("/api/wanderbook/me")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"bio\":\"\",\"homeTown\":\"\",\"isPrivate\":true}")).andExpect(status().isOk());
		this.mvc.perform(asha.as(post("/api/wanderbook/profiles/" + cara.publicId + "/follow")))
			.andExpect(status().isOk());
		this.mvc.perform(cara.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items[0].message").value("Asha asked to follow you."));
		this.mvc.perform(cara.as(post("/api/wanderbook/me/follow-requests/" + asha.publicId)))
			.andExpect(status().is2xxSuccessful());
		this.mvc.perform(asha.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items[0].type").value("follow_accepted"))
			.andExpect(jsonPath("$.items[0].message").value("Cara accepted your follow request."));
		this.mvc.perform(cara.as(get("/api/notifications"))).andExpect(jsonPath("$.items.length()").value(0));

		// Read one, then all.
		String bell = this.mvc.perform(asha.as(get("/api/notifications"))).andReturn().getResponse()
			.getContentAsString();
		int id = JsonPath.read(bell, "$.items[0].id");
		this.mvc.perform(asha.as(post("/api/notifications/read")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"ids\":[" + id + "]}")).andExpect(jsonPath("$.unread").value(0));
		this.mvc.perform(asha.as(get("/api/notifications"))).andExpect(jsonPath("$.items[0].read").value(true));
		this.mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
	}

	@Test
	void likesAddUpOnOnePostAndPushAtMostHourly() throws Exception {
		Traveller asha = traveller("Asha");
		Traveller ben = traveller("Ben");
		Traveller cara = traveller("Cara");
		Traveller dan = traveller("Dan");
		device(asha, "asha-phone");
		String postId = newPost(asha);

		this.mvc.perform(ben.as(post("/api/wanderbook/posts/" + postId + "/like"))).andExpect(status().isOk());
		this.mvc.perform(cara.as(post("/api/wanderbook/posts/" + postId + "/like"))).andExpect(status().isOk());
		this.mvc.perform(asha.as(post("/api/wanderbook/posts/" + postId + "/like"))).andExpect(status().isOk());

		this.mvc.perform(asha.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].message").value("Cara and 1 other liked your post."))
			.andExpect(jsonPath("$.items[0].postId").value(postId));
		// One push for the two likes, sent with the backend's Google token.
		assertThat(FAKE.pushes).hasSize(1);
		String push = FAKE.pushes.get(0);
		assertThat(push).contains("\"token\":\"asha-phone\"").contains("Ben liked your post.")
			.contains("\"title\":\"Wanderbook\"").contains("\"postId\":\"" + postId + "\"")
			.contains("Bearer fake-google-token");

		// After reading the bell, the next like starts a new notification and pushes again.
		this.mvc.perform(asha.as(post("/api/notifications/read"))).andExpect(jsonPath("$.unread").value(0));
		this.mvc.perform(dan.as(post("/api/wanderbook/posts/" + postId + "/like"))).andExpect(status().isOk());
		this.mvc.perform(asha.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items[0].message").value("Dan liked your post."))
			.andExpect(jsonPath("$.items[1].read").value(true));
		assertThat(FAKE.pushes).hasSize(2);
	}

	@Test
	void commentsShowTheirTextAndGoWithTheComment() throws Exception {
		Traveller asha = traveller("Asha");
		Traveller ben = traveller("Ben");
		String postId = newPost(asha);

		String comment = this.mvc.perform(ben.as(post("/api/wanderbook/posts/" + postId + "/comments"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"Lovely view!\"}")).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString();
		this.mvc.perform(asha.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items[0].message").value("Ben commented on your post:"))
			.andExpect(jsonPath("$.items[0].detail").value("Lovely view!"))
			.andExpect(jsonPath("$.items[0].postId").value(postId));

		int commentId = JsonPath.read(comment, "$.id");
		this.mvc.perform(ben.as(delete("/api/wanderbook/comments/" + commentId))).andExpect(status().isNoContent());
		this.mvc.perform(asha.as(get("/api/notifications"))).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void switchesStopPushesButNotTheBellAndGoneTokensAreForgotten() throws Exception {
		Traveller asha = traveller("Asha");
		Traveller ben = traveller("Ben");
		device(asha, "asha-phone");
		device(asha, "gone-token");

		this.mvc.perform(asha.as(put("/api/notifications/settings")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"wanderbook\":false,\"tripReminders\":true,\"sharedPlans\":true,\"announcements\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.wanderbook").value(false));
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId + "/follow")));
		assertThat(FAKE.pushes).isEmpty();
		this.mvc.perform(asha.as(get("/api/notifications"))).andExpect(jsonPath("$.items.length()").value(1));
		this.mvc.perform(asha.as(get("/api/notifications/settings"))).andExpect(jsonPath("$.wanderbook").value(false));

		this.mvc.perform(asha.as(put("/api/notifications/settings")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"wanderbook\":true,\"tripReminders\":true,\"sharedPlans\":true,\"announcements\":true}"));
		this.mvc.perform(ben.as(delete("/api/wanderbook/profiles/" + asha.publicId + "/follow")));
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId + "/follow")));
		// The phone gets it; Firebase answers 404 for the uninstalled app, whose token is deleted.
		assertThat(FAKE.pushes).hasSize(1);
		assertThat(tokens(asha)).containsExactly("asha-phone");

		// Signing out removes the phone.
		this.mvc.perform(asha.as(post("/api/notifications/devices/remove")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"asha-phone\"}")).andExpect(status().isNoContent());
		assertThat(tokens(asha)).isEmpty();
	}

	@Test
	void tripRemindersGoOutOnceAndOpeningASharedPlanTellsTheOwnerOnceADay() throws Exception {
		Traveller asha = traveller("Asha");
		device(asha, "asha-phone");
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		long goa = plan(asha, "Goa", today.plusDays(3));
		plan(asha, "Jaipur", today.plusDays(1));
		plan(asha, "Kochi", today.plusDays(9));

		this.mvc.perform(post("/api/notifications/reminders")).andExpect(status().isOk())
			.andExpect(jsonPath("$.sent").value(2));
		this.mvc.perform(post("/api/notifications/reminders")).andExpect(jsonPath("$.sent").value(0));
		this.mvc.perform(asha.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[?(@.message == 'Your trip to Goa starts in 3 days.')]").exists())
			.andExpect(jsonPath("$.items[?(@.message == 'Your trip to Jaipur starts tomorrow.')]").exists());
		assertThat(FAKE.pushes).hasSize(2).allMatch(p -> p.contains("Trip reminder"));

		this.notifications.planOpened(goa, asha.id, asha.id);
		this.notifications.planOpened(goa, asha.id, null);
		this.notifications.planOpened(goa, asha.id, null);
		this.mvc.perform(asha.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].message").value("Someone opened your shared plan “Goa in 3 days”."))
			.andExpect(jsonPath("$.items[0].planId").exists());
	}

	@Test
	void announcementsAreSentByAdminsToEveryone() throws Exception {
		Traveller asha = traveller("Asha");
		Traveller ben = traveller("Ben");
		String body = "{\"title\":\"New in GoAwayGo\",\"text\":\"Plan trips with AI.\"}";

		this.mvc.perform(ben.as(post("/api/admin/announcements")).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("not_admin"));

		this.jdbc.update("UPDATE users SET is_admin = true WHERE id = ?", asha.id);
		this.mvc.perform(asha.as(post("/api/admin/announcements")).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isOk());
		this.mvc.perform(ben.as(get("/api/notifications")))
			.andExpect(jsonPath("$.items[0].type").value("announcement"))
			.andExpect(jsonPath("$.items[0].message").value("New in GoAwayGo"))
			.andExpect(jsonPath("$.items[0].detail").value("Plan trips with AI."));
	}

	// ---- helpers ----

	private record Traveller(String token, long id, String publicId) {

		MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
			return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.token);
		}

	}

	private Traveller traveller(String name) throws Exception {
		String email = "notify-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(name,
					email)))
			.andExpect(status().isCreated());
		String session = this.mvc.perform(post("/api/auth/signin").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		Long id = this.jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		String publicId = this.jdbc.queryForObject("SELECT public_id FROM wanderbook_profiles WHERE user_id = ?",
				String.class, id);
		return new Traveller(JsonPath.read(session, "$.accessToken"), id, publicId);
	}

	private void device(Traveller traveller, String token) throws Exception {
		this.mvc.perform(traveller.as(put("/api/notifications/devices")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"%s\",\"platform\":\"android\"}".formatted(token)))
			.andExpect(status().isNoContent());
	}

	private List<String> tokens(Traveller traveller) {
		return this.jdbc.queryForList("SELECT token FROM notification_devices WHERE user_id = ?", String.class,
				traveller.id);
	}

	/** A post without photos, straight into the database (the photo upload is tested in PostFlowTests). */
	private String newPost(Traveller author) {
		return this.jdbc.queryForObject(
				"INSERT INTO wanderbook_posts (author_id, caption) VALUES (?, 'Sunset') RETURNING public_id",
				String.class, author.id);
	}

	private long plan(Traveller owner, String place, LocalDate start) {
		Long id = this.jdbc.queryForObject("""
				INSERT INTO trip_plans (user_id, place_name, country, lat, lon, start_date, days, travellers, pace,
				                        budget, currency, itinerary, model, shared)
				VALUES (?, ?, 'India', 15.3, 74.1, ?, 3, 'couple', 'balanced', 'mid', 'INR',
				        ?::jsonb, 'test', true)
				RETURNING id""", Long.class, owner.id, place, start,
				"{\"title\":\"%s in 3 days\",\"summary\":\"\",\"days\":[],\"tips\":[]}".formatted(place));
		return (id != null) ? id : 0;
	}

	/** Firebase Cloud Messaging, the metadata server's token, and OpenAI moderation (never flags). */
	static final class FakeGoogle {

		final List<String> pushes = new CopyOnWriteArrayList<>();

		private final HttpServer server;

		FakeGoogle() {
			try {
				this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			this.server.setExecutor(Executors.newCachedThreadPool());
			this.server.createContext("/", this::handle);
			this.server.start();
		}

		String url() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		private void handle(HttpExchange exchange) throws IOException {
			String path = exchange.getRequestURI().getPath();
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			int status = 200;
			String answer;
			if (path.equals("/computeMetadata/v1/instance/service-accounts/default/token")) {
				boolean fromCloudRun = "Google".equals(exchange.getRequestHeaders().getFirst("Metadata-Flavor"));
				status = fromCloudRun ? 200 : 403;
				answer = "{\"access_token\":\"fake-google-token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}";
			}
			else if (path.equals("/v1/projects/test-project/messages:send")) {
				if (body.contains("gone-token")) {
					status = 404;
					answer = "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}";
				}
				else {
					this.pushes.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
					answer = "{\"name\":\"projects/test-project/messages/1\"}";
				}
			}
			else if (path.equals("/moderations")) {
				answer = "{\"results\":[{\"flagged\":false,\"categories\":{}}]}";
			}
			else {
				status = 404;
				answer = "{}";
			}
			byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		}

	}

}
