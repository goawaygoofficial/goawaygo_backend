package com.goawaygo.backend.wanderbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;

/** Wanderbook profiles, follows, follow requests, blocks and people search, through the API. */
@SpringBootTest
class WanderbookFlowTests extends PostgresIntegrationTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	/** A signed-in test user: their token and Wanderbook public id. */
	record Traveller(String token, String publicId) {

		MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
			return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.token);
		}

	}

	@Test
	void everyAccountHasAProfileThatItsOwnerCanEdit() throws Exception {
		Traveller asha = traveller("Asha Rao");

		this.mvc.perform(asha.as(get("/api/wanderbook/me")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Asha Rao"))
			.andExpect(jsonPath("$.relationship").value("self"))
			.andExpect(jsonPath("$.isPrivate").value(false))
			.andExpect(jsonPath("$.followers").value(0))
			.andExpect(jsonPath("$.followRequests").value(0))
			.andExpect(jsonPath("$.canSeePosts").value(true));

		this.mvc.perform(asha.as(put("/api/wanderbook/me")).contentType(MediaType.APPLICATION_JSON).content("""
				{"bio":"  Mountains and old towns  ","homeTown":"Pune, India","isPrivate":false}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bio").value("Mountains and old towns"))
			.andExpect(jsonPath("$.homeTown").value("Pune, India"));

		this.mvc.perform(asha.as(put("/api/wanderbook/me")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"bio\":\"%s\",\"isPrivate\":false}".formatted("x".repeat(151))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void publicProfilesCanBeViewedSignedOutAndFollowedAtOnce() throws Exception {
		Traveller asha = traveller("Asha Public");
		Traveller ben = traveller("Ben Public");

		this.mvc.perform(get("/api/wanderbook/profiles/" + asha.publicId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Asha Public"))
			.andExpect(jsonPath("$.relationship").value("none"))
			.andExpect(jsonPath("$.canSeePosts").value(true))
			.andExpect(jsonPath("$.followRequests").doesNotExist());

		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.relationship").value("following"))
			.andExpect(jsonPath("$.followers").value(1));
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")))
			.andExpect(jsonPath("$.followers").value(1)); // again: no change

		this.mvc.perform(asha.as(get("/api/wanderbook/profiles/" + ben.publicId())))
			.andExpect(jsonPath("$.followsYou").value(true))
			.andExpect(jsonPath("$.following").value(1));
		this.mvc.perform(get("/api/wanderbook/profiles/" + asha.publicId() + "/followers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].name").value("Ben Public"))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		this.mvc.perform(ben.as(delete("/api/wanderbook/profiles/" + asha.publicId() + "/follow")))
			.andExpect(jsonPath("$.relationship").value("none"))
			.andExpect(jsonPath("$.followers").value(0));
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + ben.publicId() + "/follow")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("cannot_follow_self"));
	}

	@Test
	void privateProfilesNeedAnApprovedRequest() throws Exception {
		Traveller asha = traveller("Asha Private");
		Traveller ben = traveller("Ben Requester");
		Traveller cara = traveller("Cara Requester");
		makePrivate(asha, true);

		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")))
			.andExpect(jsonPath("$.relationship").value("requested"))
			.andExpect(jsonPath("$.canSeePosts").value(false))
			.andExpect(jsonPath("$.followers").value(0));
		this.mvc.perform(cara.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")));
		this.mvc.perform(get("/api/wanderbook/profiles/" + asha.publicId() + "/followers"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("private_profile"));

		this.mvc.perform(asha.as(get("/api/wanderbook/me/follow-requests")))
			.andExpect(jsonPath("$.items.length()").value(2));
		this.mvc.perform(asha.as(post("/api/wanderbook/me/follow-requests/" + ben.publicId())))
			.andExpect(status().isNoContent());
		this.mvc.perform(asha.as(delete("/api/wanderbook/me/follow-requests/" + cara.publicId())))
			.andExpect(status().isNoContent());

		this.mvc.perform(ben.as(get("/api/wanderbook/profiles/" + asha.publicId())))
			.andExpect(jsonPath("$.relationship").value("following"))
			.andExpect(jsonPath("$.canSeePosts").value(true));
		this.mvc.perform(cara.as(get("/api/wanderbook/profiles/" + asha.publicId())))
			.andExpect(jsonPath("$.relationship").value("none"));

		// Going public approves waiting requests.
		this.mvc.perform(cara.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")))
			.andExpect(jsonPath("$.relationship").value("requested"));
		makePrivate(asha, false);
		this.mvc.perform(cara.as(get("/api/wanderbook/profiles/" + asha.publicId())))
			.andExpect(jsonPath("$.relationship").value("following"));
		this.mvc.perform(asha.as(get("/api/wanderbook/me"))).andExpect(jsonPath("$.followers").value(2));
	}

	@Test
	void blockingHidesTheBlockerAndRemovesFollowsBothWays() throws Exception {
		Traveller asha = traveller("Asha Blocker");
		Traveller ben = traveller("Ben Blocked");
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")));
		this.mvc.perform(asha.as(post("/api/wanderbook/profiles/" + ben.publicId() + "/follow")));

		this.mvc.perform(asha.as(post("/api/wanderbook/profiles/" + ben.publicId() + "/block")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.blockedByYou").value(true))
			.andExpect(jsonPath("$.relationship").value("none"))
			.andExpect(jsonPath("$.canSeePosts").value(false));

		this.mvc.perform(ben.as(get("/api/wanderbook/profiles/" + asha.publicId())))
			.andExpect(status().isNotFound());
		this.mvc.perform(ben.as(post("/api/wanderbook/profiles/" + asha.publicId() + "/follow")))
			.andExpect(status().isNotFound());
		this.mvc.perform(asha.as(post("/api/wanderbook/profiles/" + ben.publicId() + "/follow")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("blocked"));
		this.mvc.perform(asha.as(get("/api/wanderbook/me"))).andExpect(jsonPath("$.followers").value(0))
			.andExpect(jsonPath("$.following").value(0));
		this.mvc.perform(ben.as(get("/api/wanderbook/people").param("q", "Asha Blocker")))
			.andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(asha.as(get("/api/wanderbook/me/blocked")))
			.andExpect(jsonPath("$[0].publicId").value(ben.publicId()));

		this.mvc.perform(asha.as(delete("/api/wanderbook/profiles/" + ben.publicId() + "/block")))
			.andExpect(status().isNoContent());
		this.mvc.perform(ben.as(get("/api/wanderbook/profiles/" + asha.publicId()))).andExpect(status().isOk());
	}

	@Test
	void peopleSearchFindsNamesAndListsThoseStartingWithTheTextFirst() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 6);
		traveller("Zed " + tag + "son");
		traveller(tag + " Ana");

		String body = this.mvc.perform(get("/api/wanderbook/people").param("q", tag.toUpperCase()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> names = JsonPath.read(body, "$[*].name");
		assertThat(names).containsExactly(tag + " Ana", "Zed " + tag + "son");

		this.mvc.perform(get("/api/wanderbook/people").param("q", "a")).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/wanderbook/people").param("q", "100%")).andExpect(status().isOk());
	}

	@Test
	void unknownProfilesAndSignedOutChangesAreRefused() throws Exception {
		this.mvc.perform(get("/api/wanderbook/profiles/0123456789ab")).andExpect(status().isNotFound());
		this.mvc.perform(post("/api/wanderbook/profiles/0123456789ab/follow")).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/wanderbook/me")).andExpect(status().isUnauthorized());
	}

	private void makePrivate(Traveller traveller, boolean isPrivate) throws Exception {
		this.mvc.perform(traveller.as(put("/api/wanderbook/me")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"isPrivate\":%s}".formatted(isPrivate))).andExpect(status().isOk());
	}

	private Traveller traveller(String name) throws Exception {
		String email = "wanderbook-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(name,
					email)))
			.andExpect(status().isCreated());
		String session = this.mvc.perform(post("/api/auth/signin").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String token = JsonPath.read(session, "$.accessToken");
		String me = this.mvc.perform(get("/api/wanderbook/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Traveller(token, JsonPath.read(me, "$.publicId"));
	}

}
