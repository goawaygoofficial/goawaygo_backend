package com.goawaygo.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;
import com.goawaygo.backend.external.TestFakes;

/** dev and test: only the team account signs in (and becomes admin); test runs keep their own accounts. */
@SpringBootTest(properties = { "goawaygo.team-only.emails=" + TeamOnlyAccessTests.TEAM_UPPER,
		"goawaygo.test-fakes.token=" + TeamOnlyAccessTests.TOKEN })
class TeamOnlyAccessTests extends PostgresIntegrationTest {

	static final String TEAM_UPPER = " Team-" + "Owner@Example.com ";

	static final String TEAM = "team-owner@example.com";

	static final String TOKEN = "team-only-test-run-token-0123456789";

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TestFakes testFakes;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context)
			.addFilters(this.testFakes)
			.apply(springSecurity())
			.build();
		this.jdbc.update("DELETE FROM users WHERE email = ?", TEAM);
	}

	@Test
	void someoneElseCannotSignUp() throws Exception {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(signup(email))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("team_only"));
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
			.isZero();
	}

	@Test
	void theTeamSignsUpSignsInAndIsAdmin() throws Exception {
		this.mvc.perform(signup(TEAM.toUpperCase())).andExpect(status().isCreated());
		this.mvc.perform(signin(TEAM)).andExpect(status().isOk()).andExpect(jsonPath("$.user.is_admin").value(true));
		assertThat(this.jdbc.queryForObject("SELECT is_admin FROM users WHERE email = ?", Boolean.class, TEAM))
			.isTrue();
	}

	@Test
	void testRunsKeepTheirOwnAccountsButNobodyElseCanUseThem() throws Exception {
		String email = "at-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(signup(email).header(TestFakes.HEADER, TOKEN)).andExpect(status().isCreated());
		this.mvc.perform(signin(email).header(TestFakes.HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.is_admin").value(false));
		this.mvc.perform(signin(email))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("team_only"));
	}

	@Test
	void aWrongPasswordStillLooksLikeAWrongPassword() throws Exception {
		this.mvc.perform(signin("nobody-" + UUID.randomUUID() + "@example.com"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("invalid_credentials"));
	}

	private static MockHttpServletRequestBuilder signup(String email) {
		return post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Team\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD));
	}

	private static MockHttpServletRequestBuilder signin(String email) {
		return post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD));
	}

}
