package com.goawaygo.backend.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;

/**
 * Test runs (5.5, 5.6) get the fake outside services; everyone else the real ones. Every real provider here points at a
 * closed port, so a real call can only fail: a test run that answers proves nothing left the server.
 */
@SpringBootTest(properties = "goawaygo.test-fakes.token=" + TestFakesFlowTests.TOKEN)
class TestFakesFlowTests extends PostgresIntegrationTest {

	static final String TOKEN = "test-run-token-0123456789abcdef";

	@DynamicPropertySource
	static void nowhere(DynamicPropertyRegistry registry) {
		for (String provider : List.of("gemini", "geminilite", "geminilite35", "geoapify", "unsplash", "wikipedia",
				"commons", "wikidata", "travelpayouts", "tpautocomplete", "openai")) {
			registry.add("goawaygo.external.providers." + provider + ".base-url", () -> "http://127.0.0.1:9");
			registry.add("goawaygo.external.providers." + provider + ".api-key", () -> "real-key");
			registry.add("goawaygo.external.providers." + provider + ".connect-timeout", () -> "200ms");
			registry.add("goawaygo.external.providers." + provider + ".max-attempts", () -> "1");
		}
	}

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TestFakes testFakes;

	@Autowired
	private JdbcTemplate jdbc;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context)
			.addFilters(this.testFakes)
			.apply(springSecurity())
			.build();
	}

	@Test
	void aTestRunSearchesPlacesWithoutCallingTheRealService() throws Exception {
		this.mvc.perform(get("/api/discover/places").param("q", "test").header(TestFakes.HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string(TestFakes.HEADER, "fakes"))
			.andExpect(header().string(ServerTiming.HEADER, org.hamcrest.Matchers.matchesPattern("app;dur=\\d+\\.\\d")))
			.andExpect(jsonPath("$[0].name").value("Test City 1"));
	}

	@Test
	void aTestRunsDestinationPageGetsFakeAiCardsFromTheParallelThreads() throws Exception {
		Integer cachedBefore = this.jdbc.queryForObject("SELECT count(*) FROM external_api_cache", Integer.class);
		String page = this.mvc
			.perform(get("/api/discover/destination").param("placeId", "test-place-1").header(TestFakes.HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ok"))
			.andExpect(jsonPath("$.cards.length()").value(9))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> labels = JsonPath.read(page, "$.cards[0].items[*].label");
		assertThat(labels).isNotEmpty().allSatisfy((label) -> assertThat(label).startsWith("Test "));
		// Nothing a test run sees is kept for real users.
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM external_api_cache", Integer.class))
			.isEqualTo(cachedBefore);
	}

	@Test
	void withoutTheTokenTheRealServicesAreUsed() throws Exception {
		String answer = this.mvc.perform(get("/api/discover/places").param("q", "test"))
			.andExpect(header().doesNotExist(TestFakes.HEADER))
			.andExpect(header().doesNotExist(ServerTiming.HEADER))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(answer).doesNotContain("Test City");
	}

	@Test
	void aWrongTokenIsIgnored() throws Exception {
		String answer = this.mvc
			.perform(get("/api/discover/places").param("q", "test").header(TestFakes.HEADER, "wrong-token-0123456789abcdef"))
			.andExpect(header().doesNotExist(TestFakes.HEADER))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(answer).doesNotContain("Test City");
	}

}
