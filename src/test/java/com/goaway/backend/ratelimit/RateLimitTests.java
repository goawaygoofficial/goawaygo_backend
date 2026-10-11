package com.goaway.backend.ratelimit;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goaway.backend.PostgresIntegrationTest;

/**
 * Rate limits with small values. Buckets live for the whole test class, so every test uses its own client IP and
 * email to stay independent.
 */
@SpringBootTest
@TestPropertySource(properties = { "goaway.rate-limit.enabled=true",
		"goaway.rate-limit.signin-per-ip.capacity=3", "goaway.rate-limit.signin-per-ip.period=1m",
		"goaway.rate-limit.signin-per-email.capacity=4", "goaway.rate-limit.signin-per-email.period=15m",
		"goaway.rate-limit.signup-per-ip.capacity=2", "goaway.rate-limit.signup-per-ip.period=1h" })
class RateLimitTests extends PostgresIntegrationTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@Test
	void signinIsLimitedPerIpWithA429AndRetryAfter() throws Exception {
		String ip = uniqueIp();
		for (int i = 0; i < 3; i++) {
			signin(ip, uniqueEmail()).andExpect(status().isUnauthorized()); // wrong credentials, but allowed
		}

		signin(ip, uniqueEmail()).andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.error").value("too_many_requests"))
			.andExpect(jsonPath("$.message").isNotEmpty());
	}

	@Test
	void anotherIpIsNotAffected() throws Exception {
		String blocked = uniqueIp();
		for (int i = 0; i < 4; i++) {
			signin(blocked, uniqueEmail());
		}

		signin(uniqueIp(), uniqueEmail()).andExpect(status().isUnauthorized());
	}

	@Test
	void signinIsLimitedPerEmailEvenAcrossManyIps() throws Exception {
		String victim = uniqueEmail();
		for (int i = 0; i < 4; i++) {
			signin(uniqueIp(), victim).andExpect(status().isUnauthorized());
		}

		// A fresh IP doesn't help: the account itself is protected.
		signin(uniqueIp(), victim).andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

	@Test
	void signupIsLimitedPerIp() throws Exception {
		String ip = uniqueIp();
		signup(ip).andExpect(status().isCreated());
		signup(ip).andExpect(status().isCreated());

		signup(ip).andExpect(status().isTooManyRequests());
	}

	@Test
	void otherEndpointsAreNotRateLimited() throws Exception {
		String ip = uniqueIp();
		for (int i = 0; i < 10; i++) {
			this.mvc.perform(get("/api/users/me").with(fromIp(ip))).andExpect(status().isUnauthorized());
		}
	}

	private ResultActions signin(String ip, String email) throws Exception {
		return this.mvc.perform(post("/api/auth/signin").with(fromIp(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"wrong password\"}"));
	}

	private ResultActions signup(String ip) throws Exception {
		return this.mvc.perform(post("/api/auth/signup").with(fromIp(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Rate Test\",\"email\":\"" + uniqueEmail() + "\",\"password\":\"Long-enough-1\"}"));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor fromIp(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	private static String uniqueIp() {
		int n = UUID.randomUUID().hashCode();
		return "10." + ((n >>> 16) & 0xff) + "." + ((n >>> 8) & 0xff) + "." + (n & 0xff);
	}

	private static String uniqueEmail() {
		return "rate-" + UUID.randomUUID() + "@example.com";
	}

}
