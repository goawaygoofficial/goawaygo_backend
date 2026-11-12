package com.goawaygo.backend.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TestFakesTests {

	private static final String TOKEN = "test-run-token-0123456789abcdef";

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void onlyTheRightTokenSwitchesTheRequestToFakesAndOnlyForThatRequest() throws Exception {
		TestFakes filter = new TestFakes(TOKEN);
		assertThat(seenActive(filter, TOKEN)).isTrue();
		assertThat(seenActive(filter, "wrong-token-0123456789abcdef")).isFalse();
		assertThat(seenActive(filter, null)).isFalse();
		assertThat(TestFakes.active()).isFalse();
	}

	@Test
	void noTokenOrAShortOneMeansOff() throws Exception {
		assertThat(seenActive(new TestFakes(""), "")).isFalse();
		assertThat(seenActive(new TestFakes("short"), "short")).isFalse();
	}

	@Test
	void carriesATestRunOntoAnotherThread() throws Exception {
		AtomicBoolean seen = new AtomicBoolean();
		Runnable task = TestFakes.during(() -> TestFakes.carry(() -> seen.set(TestFakes.active())));
		Thread other = new Thread(task);
		other.start();
		other.join();
		assertThat(seen).isTrue();
		// Outside a test run nothing changes.
		AtomicBoolean real = new AtomicBoolean(true);
		Thread plain = new Thread(TestFakes.carry(() -> real.set(TestFakes.active())));
		plain.start();
		plain.join();
		assertThat(real).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void aFakeAiAnswerFollowsTheRequestedSchemaWithEnoughDaysForAnyTrip() {
		Map<String, Object> slot = Map.of("type", "OBJECT", "properties",
				Map.of("place", Map.of("type", "STRING"), "cost", Map.of("type", "INTEGER")));
		Map<String, Object> schema = Map.of("type", "OBJECT", "properties",
				Map.of("title", Map.of("type", "STRING"), "days",
						Map.of("type", "ARRAY", "items", Map.of("type", "OBJECT", "properties", Map.of("morning", slot))),
						"kind", Map.of("type", "STRING", "enum", List.of("city", "nature")), "link",
						Map.of("type", "STRING")));
		Map<String, Object> answer = (Map<String, Object>) SchemaFaker.fake(schema, "answer", 0);
		assertThat(answer.get("title")).isEqualTo("Test title 1");
		assertThat((List<Object>) answer.get("days")).hasSize(SchemaFaker.DAYS);
		assertThat(answer.get("kind")).isEqualTo("city");
		assertThat(answer.get("link")).isEqualTo("https://example.com/");
	}

	@Test
	void unknownRequestsFailLoudly() {
		FakeHttp http = new FakeHttp("someprovider", this.json);
		assertThat(http.answer(HttpMethod.GET, URI.create("https://x.test/anything"), new byte[0])).isNull();
	}

	@Test
	void partnerLinksAndModerationAnswerPerItem() {
		FakeHttp travelpayouts = new FakeHttp("travelpayouts", this.json);
		FakeHttp.Answer links = travelpayouts.answer(HttpMethod.POST, URI.create("https://api.travelpayouts.com/links/v1/create"),
				"{\"links\":[{\"url\":\"https://a.test\"},{\"url\":\"https://b.test\"}]}".getBytes(StandardCharsets.UTF_8));
		assertThat(this.json.writeValueAsString(links.body())).contains("https://a.test").contains("https://b.test");
		FakeHttp openai = new FakeHttp("openai", this.json);
		FakeHttp.Answer moderation = openai.answer(HttpMethod.POST, URI.create("https://api.openai.com/v1/moderations"),
				"{\"input\":[{\"type\":\"text\"},{\"type\":\"image_url\"}]}".getBytes(StandardCharsets.UTF_8));
		assertThat(this.json.writeValueAsString(moderation.body())).contains("\"flagged\":false");
	}

	private static boolean seenActive(TestFakes filter, String header) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/discover/places");
		if (header != null) {
			request.addHeader(TestFakes.HEADER, header);
		}
		AtomicBoolean seen = new AtomicBoolean();
		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				seen.set(TestFakes.active());
			}
		});
		return seen.get();
	}

}
