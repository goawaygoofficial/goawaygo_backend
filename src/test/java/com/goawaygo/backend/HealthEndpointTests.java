package com.goawaygo.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The uptime check: public, status only, and the rest of Actuator stays closed. */
@SpringBootTest
class HealthEndpointTests extends PostgresIntegrationTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@Test
	void healthIsPublicAndShowsOnlyTheStatus() throws Exception {
		this.mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
	}

	@Test
	void otherActuatorEndpointsAreNotReachable() throws Exception {
		for (String path : new String[] { "/actuator", "/actuator/env", "/actuator/info", "/actuator/health/db" }) {
			this.mvc.perform(get(path)).andExpect(status().is4xxClientError());
		}
	}

}
