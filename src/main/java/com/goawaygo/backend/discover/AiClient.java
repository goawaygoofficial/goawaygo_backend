package com.goawaygo.backend.discover;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.goawaygo.backend.external.ExternalApis;

/**
 * Asks Gemini for a JSON answer, trying the configured models in order: when one is out of free quota (our usage
 * limit or Google's 429), overloaded (503), too slow or gives no usable answer, the next one is asked. Each request is
 * independent (no chat history), so earlier answers can never influence later ones.
 */
@Component
public class AiClient {

	private static final Logger log = LoggerFactory.getLogger(AiClient.class);

	private final ExternalApis apis;

	private final DiscoverProperties properties;

	public AiClient(ExternalApis apis, DiscoverProperties properties) {
		this.apis = apis;
		this.properties = properties;
	}

	/** The answer's JSON text and the model that wrote it. */
	public record Answer(String json, String model) {
	}

	/**
	 * @param schema the answer's JSON schema (Gemini {@code responseSchema} format)
	 * @return the first usable answer, or null if no model could answer
	 */
	public Answer generateJson(List<DiscoverProperties.AiModel> models, String system, String request,
			Map<String, Object> schema, double temperature, int maxOutputTokens) {
		Instant deadline = Instant.now().plus(this.properties.aiDeadline());
		for (DiscoverProperties.AiModel model : models) {
			if (Instant.now().isAfter(deadline)) {
				log.warn("AI deadline of {} reached; no model answered", this.properties.aiDeadline());
				return null;
			}
			try {
				String json = ask(model, system, request, schema, temperature, maxOutputTokens);
				if (json != null && !json.isBlank()) {
					return new Answer(json, model.model());
				}
				log.warn("{} gave no answer text; trying the next model", model.model());
			}
			catch (RuntimeException ex) {
				// Out of quota, overloaded, timed out, model unavailable, ...: the next model may still answer.
				log.warn("{} failed ({}); trying the next model", model.model(), ex.toString());
			}
		}
		return null;
	}

	private String ask(DiscoverProperties.AiModel model, String system, String request, Map<String, Object> schema,
			double temperature, int maxOutputTokens) {
		Map<String, Object> generation = new LinkedHashMap<>();
		generation.put("temperature", temperature);
		generation.put("seed", 7);
		generation.put("maxOutputTokens", maxOutputTokens);
		generation.put("responseMimeType", "application/json");
		generation.put("responseSchema", schema);
		if (model.thinkingLevel() != null && !model.thinkingLevel().isBlank()) {
			generation.put("thinkingConfig", Map.of("thinkingLevel", model.thinkingLevel()));
		}
		Map<String, Object> body = Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
				"contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request)))),
				"generationConfig", generation);
		long started = System.nanoTime();
		GeminiResponse response = this.apis.client(model.provider())
			.call((http, apiKey) -> http.post()
				.uri("/models/{model}:generateContent", model.model())
				.header("x-goog-api-key", apiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(GeminiResponse.class));
		log.info("{} answered in {} ms", model.model(), Duration.ofNanos(System.nanoTime() - started).toMillis());
		return (response != null) ? response.text() : null;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GeminiResponse(List<Candidate> candidates) {

		/** The answer text, without any "thinking" parts. */
		String text() {
			if (this.candidates == null || this.candidates.isEmpty() || this.candidates.get(0).content() == null
					|| this.candidates.get(0).content().parts() == null) {
				return null;
			}
			StringBuilder text = new StringBuilder();
			for (Part part : this.candidates.get(0).content().parts()) {
				if (part.text() != null && !Boolean.TRUE.equals(part.thought())) {
					text.append(part.text());
				}
			}
			return text.toString();
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Candidate(Content content) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Content(List<Part> parts) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Part(String text, Boolean thought) {
	}

}
