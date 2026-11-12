package com.goawaygo.backend.wanderbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;
import com.goawaygo.backend.mail.RecordingEmailSender;

/**
 * Wanderbook photos, posts, the feed, likes, comments, reports and review, against local fakes of R2, OpenAI
 * moderation and Geoapify. The fake moderation flags PNG photos and text containing "FORBIDDEN".
 */
@SpringBootTest
class PostFlowTests extends PostgresIntegrationTest {

	private static final Fakes FAKES = new Fakes();

	@DynamicPropertySource
	static void outsideServices(DynamicPropertyRegistry registry) {
		registry.add("goawaygo.media.endpoint", FAKES::url);
		registry.add("goawaygo.media.access-key-id", () -> "test-key-id");
		registry.add("goawaygo.media.secret-access-key", () -> "test-secret");
		registry.add("goawaygo.media.public-base-url", () -> "https://media.test");
		for (String provider : List.of("openai", "geoapify")) {
			registry.add("goawaygo.external.providers." + provider + ".base-url", FAKES::url);
			registry.add("goawaygo.external.providers." + provider + ".api-key", () -> "test-key");
		}
		registry.add("goawaygo.external.providers.openai.max-attempts", () -> "1");
	}

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private RecordingEmailSender emails;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PostService postService;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		FAKES.moderationDown = false;
	}

	record Traveller(long userId, String token, String publicId) {

		<B extends AbstractMockHttpServletRequestBuilder<B>> B as(B request) {
			return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.token);
		}

	}

	@Test
	void anUploadKeepsThePictureLosesItsLocationAndSuggestsThePlace() throws Exception {
		Traveller asha = traveller("Asha Uploader");
		String body = this.mvc.perform(asha.as(multipart("/api/wanderbook/photos").file(jpeg("paris.jpg", true))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.photo.width").value(40))
			.andExpect(jsonPath("$.suggestedPlace.name").value("Paris"))
			.andExpect(jsonPath("$.suggestedPlace.region").value("Ile-de-France"))
			.andExpect(jsonPath("$.suggestedPlace.country").value("France"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain("48.85").doesNotContain("latitude");

		String url = JsonPath.read(body, "$.photo.url");
		assertThat(url).startsWith("https://media.test/p/").endsWith(".jpg");
		byte[] stored = FAKES.objects.get("/goawaygo-media/" + url.substring("https://media.test/".length()));
		assertThat(stored).isNotNull();
		assertThat(((JpegImageMetadata) Imaging.getMetadata(stored)).getExif().getGpsInfo()).isNull();
		assertThat(FAKES.lastAuthorization).startsWith("AWS4-HMAC-SHA256 Credential=test-key-id/");
	}

	@Test
	void flaggedPhotosAndTextAreRefusedAndModerationOutagesBlockPosting() throws Exception {
		Traveller asha = traveller("Asha Flagged");
		int before = FAKES.objects.size();
		this.mvc.perform(asha.as(multipart("/api/wanderbook/photos").file(png())))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error").value("content_not_allowed"));
		assertThat(FAKES.objects).hasSize(before);

		String photo = upload(asha);
		this.mvc.perform(asha.as(post("/api/wanderbook/posts")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoIds\":[\"%s\"],\"caption\":\"this is FORBIDDEN\"}".formatted(photo)))
			.andExpect(status().isUnprocessableContent());

		FAKES.moderationDown = true;
		this.mvc.perform(asha.as(post("/api/wanderbook/posts")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoIds\":[\"%s\"],\"caption\":\"Sunset\"}".formatted(photo)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("moderation_unavailable"));
		FAKES.moderationDown = false;
		// The photo is still unused, so it can be posted now.
		newPost(asha, List.of(photo), "Sunset");
	}

	@Test
	void postsShowInTheSharedFeedWithTheirPhotosPlaceAndCounts() throws Exception {
		Traveller asha = traveller("Asha Poster");
		String first = upload(asha);
		String second = upload(asha);
		String post = this.mvc.perform(asha.as(post("/api/wanderbook/posts")).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"photoIds":["%s","%s"],"caption":"Two days in Paris",
					 "place":{"name":"Paris","region":"Ile-de-France","country":"France"}}""".formatted(second, first)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.photos.length()").value(2))
			.andExpect(jsonPath("$.photos[0].id").value(second))
			.andExpect(jsonPath("$.place.name").value("Paris"))
			.andExpect(jsonPath("$.isYours").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String postId = JsonPath.read(post, "$.publicId");

		String feed = this.mvc.perform(get("/api/wanderbook/feed"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> ids = JsonPath.read(feed, "$.items[*].publicId");
		assertThat(ids).contains(postId);
		this.mvc.perform(get("/api/wanderbook/posts/" + postId))
			.andExpect(jsonPath("$.author.name").value("Asha Poster"))
			.andExpect(jsonPath("$.likedByYou").value(false))
			.andExpect(jsonPath("$.isYours").value(false));
		this.mvc.perform(get("/api/wanderbook/profiles/" + asha.publicId() + "/posts"))
			.andExpect(jsonPath("$.items[0].publicId").value(postId));
		this.mvc.perform(get("/api/wanderbook/profiles/" + asha.publicId())).andExpect(jsonPath("$.posts").value(1));

		// A photo can be in one post only, and only its owner can use it.
		Traveller ben = traveller("Ben Thief");
		this.mvc.perform(ben.as(post("/api/wanderbook/posts")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoIds\":[\"%s\"]}".formatted(first)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_photos"));
		this.mvc.perform(asha.as(post("/api/wanderbook/posts")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoIds\":[\"%s\"]}".formatted(first)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void privateAndBlockedPostsStayHidden() throws Exception {
		Traveller asha = traveller("Asha Hidden");
		Traveller ben = traveller("Ben Follower");
		Traveller cara = traveller("Cara Stranger");
		String postId = newPost(asha, List.of(upload(asha)), "Only for friends");
		this.mvc.perform(asha.as(put("/api/wanderbook/me")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"isPrivate\":true}")).andExpect(status().isOk());
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")));
		this.mvc.perform(asha.as(post("/api/wanderbook/me/follow-requests/" + ben.publicId())));

		this.mvc.perform(get("/api/wanderbook/posts/" + postId)).andExpect(status().isNotFound());
		this.mvc.perform(cara.as(get("/api/wanderbook/profiles/" + asha.publicId() + "/posts")))
			.andExpect(status().isForbidden());
		assertThat(feedIds(cara)).doesNotContain(postId);
		assertThat(feedIds(ben)).contains(postId);

		this.mvc.perform(asha.as(post("/api/wanderbook/profiles/" + ben.publicId() + "/block")));
		assertThat(feedIds(ben)).doesNotContain(postId);
		this.mvc.perform(ben.as(get("/api/wanderbook/posts/" + postId))).andExpect(status().isNotFound());
	}

	@Test
	void likesAndCommentsWithTheirDeleteRules() throws Exception {
		Traveller asha = traveller("Asha Author");
		Traveller ben = traveller("Ben Commenter");
		Traveller cara = traveller("Cara Other");
		String postId = newPost(asha, List.of(upload(asha)), "Lake day");

		this.mvc.perform(ben.as(post("/api/wanderbook/posts/" + postId + "/like")))
			.andExpect(jsonPath("$.likes").value(1))
			.andExpect(jsonPath("$.likedByYou").value(true));
		this.mvc.perform(ben.as(post("/api/wanderbook/posts/" + postId + "/like"))).andExpect(jsonPath("$.likes").value(1));
		this.mvc.perform(get("/api/wanderbook/posts/" + postId + "/likes"))
			.andExpect(jsonPath("$[0].name").value("Ben Commenter"));
		this.mvc.perform(ben.as(delete("/api/wanderbook/posts/" + postId + "/like"))).andExpect(jsonPath("$.likes").value(0));

		long benComment = comment(ben, postId, "Beautiful!");
		long caraComment = comment(cara, postId, "Where is this?");
		this.mvc.perform(ben.as(post("/api/wanderbook/posts/" + postId + "/comments"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"FORBIDDEN words\"}")).andExpect(status().isUnprocessableContent());
		this.mvc.perform(asha.as(get("/api/wanderbook/posts/" + postId + "/comments")))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].text").value("Beautiful!"))
			.andExpect(jsonPath("$.items[0].canDelete").value(true));
		this.mvc.perform(get("/api/wanderbook/posts/" + postId)).andExpect(jsonPath("$.comments").value(2));

		this.mvc.perform(cara.as(delete("/api/wanderbook/comments/" + benComment))).andExpect(status().isNotFound());
		this.mvc.perform(ben.as(delete("/api/wanderbook/comments/" + benComment))).andExpect(status().isNoContent());
		this.mvc.perform(asha.as(delete("/api/wanderbook/comments/" + caraComment))).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/wanderbook/posts/" + postId)).andExpect(jsonPath("$.comments").value(0));
	}

	@Test
	void onlyTheAuthorEditsOrDeletesAndDeletingRemovesThePhotoFiles() throws Exception {
		Traveller asha = traveller("Asha Editor");
		Traveller ben = traveller("Ben Not Author");
		String photo = upload(asha);
		String postId = newPost(asha, List.of(photo), "Draft caption");

		this.mvc.perform(ben.as(put("/api/wanderbook/posts/" + postId)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"caption\":\"Hacked\"}")).andExpect(status().isNotFound());
		this.mvc.perform(asha.as(put("/api/wanderbook/posts/" + postId)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"caption\":\"Final caption\",\"place\":{\"name\":\"Hallstatt\",\"country\":\"Austria\"}}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.caption").value("Final caption"))
			.andExpect(jsonPath("$.place.name").value("Hallstatt"))
			.andExpect(jsonPath("$.editedAt").exists());

		String key = objectKeyOf(photo);
		assertThat(FAKES.objects).containsKey(key);
		this.mvc.perform(ben.as(delete("/api/wanderbook/posts/" + postId))).andExpect(status().isNotFound());
		this.mvc.perform(asha.as(delete("/api/wanderbook/posts/" + postId))).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/wanderbook/posts/" + postId)).andExpect(status().isNotFound());
		assertThat(FAKES.objects).doesNotContainKey(key);
	}

	@Test
	void reportsAreEmailedOnceAndAdminsRemoveOrDismissThem() throws Exception {
		Traveller asha = traveller("Asha Reported");
		Traveller ben = traveller("Ben Reporter");
		String postId = newPost(asha, List.of(upload(asha)), "Something odd");
		int emailsBefore = this.emails.sentTo("team@goawaygo.com").size();

		for (int i = 0; i < 2; i++) {
			this.mvc.perform(ben.as(post("/api/wanderbook/reports")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetType\":\"post\",\"target\":\"%s\",\"reason\":\"spam\",\"details\":\"Ads\"}"
					.formatted(postId)))
				.andExpect(status().isNoContent());
		}
		assertThat(this.emails.sentTo("team@goawaygo.com")).hasSize(emailsBefore + 1);
		this.mvc.perform(asha.as(post("/api/wanderbook/reports")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"user\",\"target\":\"%s\",\"reason\":\"spam\"}".formatted(asha.publicId())))
			.andExpect(status().isBadRequest());

		this.mvc.perform(ben.as(get("/api/admin/wanderbook/reports"))).andExpect(status().isForbidden());
		this.jdbc.update("UPDATE users SET is_admin = true WHERE id = ?", ben.userId());
		String reports = this.mvc.perform(ben.as(get("/api/admin/wanderbook/reports")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<Integer> reportIds = JsonPath.read(reports,
				"$[?(@.target.publicId == '%s')].id".formatted(postId));
		assertThat(reportIds).hasSize(1);
		this.mvc.perform(ben.as(post("/api/admin/wanderbook/reports/" + reportIds.get(0) + "/resolve"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"remove\"}")).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/wanderbook/posts/" + postId)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + ben.token()))
			.andExpect(jsonPath("$.is_admin").value(true));
	}

	@Test
	void profilePhotoAndCoverComeFromUploads() throws Exception {
		Traveller asha = traveller("Asha Avatar");
		String avatar = upload(asha);
		String cover = upload(asha);

		this.mvc.perform(asha.as(put("/api/wanderbook/me/avatar")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoId\":\"%s\"}".formatted(avatar)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.photoUrl").value("https://media.test/" + objectKeyOf(avatar).substring(
					"/goawaygo-media/".length())));
		this.mvc.perform(asha.as(put("/api/wanderbook/me/cover")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoId\":\"%s\"}".formatted(cover)))
			.andExpect(jsonPath("$.coverUrl").isNotEmpty());
		this.mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + asha.token()))
			.andExpect(jsonPath("$.profile_picture").value(org.hamcrest.Matchers.startsWith("https://media.test/p/")));

		// A profile photo can't also be posted, and replacing it deletes the old file.
		this.mvc.perform(asha.as(post("/api/wanderbook/posts")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoIds\":[\"%s\"]}".formatted(avatar))).andExpect(status().isBadRequest());
		String oldKey = objectKeyOf(avatar);
		this.mvc.perform(asha.as(put("/api/wanderbook/me/avatar")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoId\":null}")).andExpect(jsonPath("$.photoUrl").doesNotExist());
		assertThat(FAKES.objects).doesNotContainKey(oldKey);
	}

	@Test
	void uploadsNeverUsedAreDeletedAfterADay() throws Exception {
		Traveller asha = traveller("Asha Abandoned");
		String photo = upload(asha);
		String key = objectKeyOf(photo);
		this.jdbc.update("UPDATE wanderbook_photos SET created_at = now() - interval '2 days' WHERE public_id = ?",
				photo);

		this.postService.deleteAbandonedUploads();

		assertThat(FAKES.objects).doesNotContainKey(key);
		assertThat(this.jdbc.queryForList("SELECT 1 FROM wanderbook_photos WHERE public_id = ?", photo)).isEmpty();
	}

	// ---- helpers ----

	private List<String> feedIds(Traveller viewer) throws Exception {
		String feed = this.mvc.perform(viewer.as(get("/api/wanderbook/feed")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(feed, "$.items[*].publicId");
	}

	private String upload(Traveller traveller) throws Exception {
		String body = this.mvc.perform(traveller.as(multipart("/api/wanderbook/photos").file(jpeg("x.jpg", false))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.photo.id");
	}

	private String newPost(Traveller traveller, List<String> photoIds, String caption) throws Exception {
		String ids = String.join("\",\"", photoIds);
		String body = this.mvc.perform(traveller.as(post("/api/wanderbook/posts")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoIds\":[\"%s\"],\"caption\":\"%s\"}".formatted(ids, caption)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.publicId");
	}

	private long comment(Traveller traveller, String postId, String text) throws Exception {
		String body = this.mvc.perform(traveller.as(post("/api/wanderbook/posts/" + postId + "/comments"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"%s\"}".formatted(text)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private String objectKeyOf(String photoPublicId) {
		return "/goawaygo-media/" + this.jdbc.queryForObject(
				"SELECT object_key FROM wanderbook_photos WHERE public_id = ?", String.class, photoPublicId);
	}

	private Traveller traveller(String name) throws Exception {
		String email = "posts-" + UUID.randomUUID() + "@example.com";
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
		String token = JsonPath.read(session, "$.accessToken");
		long userId = ((Number) JsonPath.read(session, "$.user.id")).longValue();
		String me = this.mvc.perform(get("/api/wanderbook/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Traveller(userId, token, JsonPath.read(me, "$.publicId"));
	}

	private static MockMultipartFile jpeg(String name, boolean withGps) throws Exception {
		BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		byte[] bytes = out.toByteArray();
		if (withGps) {
			TiffOutputSet exif = new TiffOutputSet();
			exif.setGpsInDegrees(2.2945, 48.8584);
			ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
			new ExifRewriter().updateExifMetadataLossless(bytes, rewritten, exif);
			bytes = rewritten.toByteArray();
		}
		return new MockMultipartFile("file", name, "image/jpeg", bytes);
	}

	private static MockMultipartFile png() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", out);
		return new MockMultipartFile("file", "bad.png", "image/png", out.toByteArray());
	}

	/** Local stand-ins for R2 (S3 PUT/DELETE), OpenAI moderation and Geoapify reverse geocoding. */
	static final class Fakes {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();

		volatile String lastAuthorization;

		volatile boolean moderationDown;

		private final HttpServer server;

		Fakes() {
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
			byte[] body = exchange.getRequestBody().readAllBytes();
			int status = 200;
			String answer = "";
			if (path.startsWith("/goawaygo-media/")) {
				this.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
				if (exchange.getRequestMethod().equals("PUT")) {
					this.objects.put(path, body);
				}
				else if (exchange.getRequestMethod().equals("DELETE")) {
					this.objects.remove(path);
					status = 204;
				}
			}
			else if (path.equals("/moderations")) {
				String request = new String(body, StandardCharsets.UTF_8);
				if (this.moderationDown) {
					status = 500;
					answer = "{}";
				}
				else {
					boolean flagged = request.contains("data:image/png") || request.contains("FORBIDDEN");
					answer = "{\"results\":[{\"flagged\":%s,\"categories\":{\"spam\":%s}}]}".formatted(flagged,
							flagged);
				}
			}
			else if (path.equals("/v1/geocode/reverse")) {
				answer = """
						{"results":[{"place_id":"pid","name":"Champ de Mars","city":"Paris","state":"Ile-de-France",
						 "country":"France","country_code":"fr","lat":48.85,"lon":2.29}]}""";
			}
			else {
				status = 404;
				answer = "{}";
			}
			byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, (status == 204) ? -1 : bytes.length);
			if (status != 204) {
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		}

	}

}
