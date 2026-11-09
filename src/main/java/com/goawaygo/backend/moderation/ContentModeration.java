package com.goawaygo.backend.moderation;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.external.ExternalApis;

/**
 * Checks photos and text before they appear in Wanderbook, with OpenAI's free moderation model
 * ({@code omni-moderation-latest}: sexual content and minors, violence and gore, self-harm, hate, harassment,
 * illicit activity). Flagged content is refused with a message pointing to the Community Guidelines. When the check
 * can't run (OpenAI down or not configured), nothing is published unchecked: the user is asked to try again.
 */
@Component
public class ContentModeration {

	static final String PROVIDER = "openai";

	static final String MODEL = "omni-moderation-latest";

	private static final Logger log = LoggerFactory.getLogger(ContentModeration.class);

	private final ExternalApis apis;

	public ContentModeration(ExternalApis apis) {
		this.apis = apis;
	}

	/** Checks text (captions, comments, bios). Blank text passes without a call. */
	public void checkText(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		check(List.of(Map.of("type", "text", "text", text)), "text");
	}

	/** Checks a photo (sent as a data URL; nothing is stored by us at OpenAI). */
	public void checkImage(byte[] content, String contentType) {
		String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(content);
		check(List.of(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))), "photo");
	}

	private void check(List<Map<String, Object>> input, String what) {
		ModerationResponse response;
		try {
			response = this.apis.client(PROVIDER)
				.call((http, apiKey) -> http.post()
					.uri("/moderations")
					.header("Authorization", "Bearer " + apiKey)
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("model", MODEL, "input", input))
					.retrieve()
					.body(ModerationResponse.class));
		}
		catch (RuntimeException ex) {
			log.error("Moderation of a {} failed: {}", what, ex.toString());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "moderation_unavailable",
					"We couldn't check this right now. Please try again in a minute.");
		}
		if (response == null || response.results() == null || response.results().isEmpty()) {
			log.error("Moderation of a {} gave no result", what);
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "moderation_unavailable",
					"We couldn't check this right now. Please try again in a minute.");
		}
		for (Result result : response.results()) {
			if (result.flagged()) {
				TreeSet<String> categories = new TreeSet<>();
				if (result.categories() != null) {
					result.categories().forEach((name, flagged) -> {
						if (Boolean.TRUE.equals(flagged)) {
							categories.add(name);
						}
					});
				}
				log.info("Refused a {} flagged by moderation: {}", what, categories);
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "content_not_allowed",
						"photo".equals(what) ? "This photo goes against our Community Guidelines, so it can't be posted."
								: "This text goes against our Community Guidelines, so it can't be posted.");
			}
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ModerationResponse(List<Result> results) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Result(boolean flagged, Map<String, Boolean> categories) {
	}

}
