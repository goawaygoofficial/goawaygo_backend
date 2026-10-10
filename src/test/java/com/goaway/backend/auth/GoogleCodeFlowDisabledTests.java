package com.goaway.backend.auth;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goaway.backend.PostgresIntegrationTest;

/** Without a client secret the code flow is off: a clear 503, and Google is never called. */
@SpringBootTest(properties = "goaway.auth.google.client-secret=")
class GoogleCodeFlowDisabledTests extends PostgresIntegrationTest {

	@Autowired
	private WebApplicationContext context;

	@Test
	void codeFlowWithoutAClientSecretIsUnavailable() throws Exception {
		MockMvcBuilders.webAppContextSetup(this.context)
			.apply(springSecurity())
			.build()
			.perform(post("/api/auth/google-signin/code").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"anything\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("google_sign_in_unavailable"));
	}

}
