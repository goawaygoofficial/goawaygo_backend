package com.goawaygo.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.goawaygo.backend.PostgresIntegrationTest;
import com.goawaygo.backend.common.i18n.Lang;
import com.goawaygo.backend.common.i18n.T;
import com.goawaygo.backend.mail.RecordingEmailSender;

/**
 * Global readiness (4.7): errors, regions, the bell and emails in the user's language, and translation files that
 * keep every placeholder.
 */
@SpringBootTest
class LanguageTests extends PostgresIntegrationTest {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

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
	}

	@Test
	void everyLanguageTranslatesTheSameSentencesWithTheSamePlaceholders() {
		Map<String, String> hindi = T.all(Lang.HI);
		assertThat(hindi).hasSizeGreaterThan(100);
		for (Lang lang : Lang.values()) {
			if (lang.isEnglish()) {
				continue;
			}
			Map<String, String> all = T.all(lang);
			assertThat(all.keySet()).as(lang.code()).isEqualTo(hindi.keySet());
			all.forEach((english, translated) -> {
				assertThat(translated).as(lang.code() + ": " + english).isNotBlank();
				Matcher m = PLACEHOLDER.matcher(english);
				while (m.find()) {
					assertThat(translated).as(lang.code() + ": " + english).contains(m.group());
				}
			});
		}
		assertThat(T.t(Lang.ES, "Your trip to {0} starts in {1} days.", "Goa", 3))
			.isEqualTo("Tu viaje a Goa empieza dentro de 3 días.");
		assertThat(T.t(Lang.JA, "A sentence nobody translated.")).isEqualTo("A sentence nobody translated.");
		assertThat(Lang.of("hi-IN")).isEqualTo(Lang.HI);
		assertThat(Lang.orEnglish("pt-BR")).isEqualTo(Lang.EN);
	}

	@Test
	void errorsAndRegionsSpeakTheRequestsLanguage() throws Exception {
		this.mvc.perform(get("/api/notifications").header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR,fr;q=0.9"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("Connectez-vous pour faire cela."));
		this.mvc.perform(post("/api/auth/signin").header(HttpHeaders.ACCEPT_LANGUAGE, "de")
			.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.com\",\"password\":\"wrong pass\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("E-Mail-Adresse oder Passwort ist falsch."));
		// Validation: our own messages are translated.
		this.mvc.perform(post("/api/auth/signup").header(HttpHeaders.ACCEPT_LANGUAGE, "es")
			.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Ana\",\"email\":\"ana@example.com\",\"password\":\"short\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Revisa los campos marcados."))
			.andExpect(jsonPath("$.fieldErrors.password").value("La contraseña debe tener entre 8 y 72 caracteres."));
		// No header, or a language we don't speak: English.
		this.mvc.perform(get("/api/notifications").header(HttpHeaders.ACCEPT_LANGUAGE, "pt-BR"))
			.andExpect(jsonPath("$.message").value("You need to sign in to do this."));

		this.mvc.perform(get("/api/regions").header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
			.andExpect(status().isOk())
			.andExpect(header().stringValues(HttpHeaders.VARY,
					org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Accept-Language"))))
			.andExpect(jsonPath("$.countries[?(@.code == 'IN')].name").value("インド"))
			.andExpect(jsonPath("$.countries[?(@.code == 'DE')].name").value("ドイツ"));
		this.mvc.perform(get("/api/regions").header(HttpHeaders.ACCEPT_LANGUAGE, "hi"))
			.andExpect(jsonPath("$.countries[?(@.code == 'IN')].name").value("भारत"));
	}

	@Test
	void anAccountKeepsItsLanguageForTheBellPushesAndEmails() throws Exception {
		String email = "lang-" + UUID.randomUUID() + "@example.com";
		this.mvc.perform(post("/api/auth/signup").header(HttpHeaders.ACCEPT_LANGUAGE, "es")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Ana\",\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.language").value("es"));
		assertThat(this.emails.latestTo(email).orElseThrow().subject()).isEqualTo("Verifica tu correo para GoAwayGo");
		assertThat(this.emails.latestTo(email).orElseThrow().text()).startsWith("Hola, Ana:");

		String session = this.mvc.perform(post("/api/auth/signin").header("X-Client-Type", "mobile")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"correct horse battery\"}".formatted(email)))
			.andReturn().getResponse().getContentAsString();
		String token = "Bearer " + JsonPath.read(session, "$.accessToken");

		this.mvc.perform(put("/api/users/me/language").header(HttpHeaders.AUTHORIZATION, token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"ar\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.language").value("ar"));
		this.mvc.perform(put("/api/users/me/language").header(HttpHeaders.AUTHORIZATION, token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"xx\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_language"));

		// Emails follow the account's language, whatever the request says.
		this.mvc.perform(post("/api/auth/forgot-password").header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"%s\"}".formatted(email)))
			.andExpect(status().isAccepted());
		var reset = this.emails.latestTo(email).orElseThrow();
		assertThat(reset.subject()).isEqualTo("أعد تعيين كلمة مرور GoAwayGo");
		assertThat(reset.html()).contains("dir=\"rtl\"");

		// The bell is written in the reader's language.
		Long id = this.jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		this.jdbc.update("""
				INSERT INTO notifications (user_id, type, title, text) VALUES (?, 'announcement', 'Hola', 'Bienvenida')""",
				id);
		this.jdbc.update("INSERT INTO notifications (user_id, type, actor_id) VALUES (?, 'follow', ?)", id, id);
		this.mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, token)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "es"))
			.andExpect(jsonPath("$.items[0].message").value("Ana ha empezado a seguirte."))
			.andExpect(jsonPath("$.items[1].message").value("Hola"));
	}

}
