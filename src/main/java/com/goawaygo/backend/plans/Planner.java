package com.goawaygo.backend.plans;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.goawaygo.backend.discover.AiClient;

/**
 * Asks the AI for a trip plan or for one day of it (prompts in {@code prompts/plan-*.txt}, answers in
 * {@link Itinerary}'s JSON schema). Each request is independent: the plan's other days are described in the request,
 * never as chat history.
 */
@Component
class Planner {

	private static final Logger log = LoggerFactory.getLogger(Planner.class);

	/** Some variety between plans, while staying factual. */
	private static final double TEMPERATURE = 0.4;

	private static final int PLAN_TOKENS = 12_000;

	private static final int DAY_TOKENS = 2_000;

	private final AiClient ai;

	private final PlanProperties properties;

	private final ObjectMapper json;

	private final String system = prompt("prompts/plan-system.txt");

	private final String planRequest = prompt("prompts/plan-request.txt");

	private final String dayRequest = prompt("prompts/plan-day-request.txt");

	Planner(AiClient ai, PlanProperties properties, ObjectMapper json) {
		this.ai = ai;
		this.properties = properties;
		this.json = json;
	}

	/** What a plan is for: the trip as the traveller described it. */
	record Trip(String place, LocalDate startDate, int days, String travellers, String pace, String budget,
			List<String> interests, String currency) {
	}

	/** A cleaned-up itinerary and the model that wrote it. */
	record Written(Itinerary itinerary, String model) {
	}

	/** @return the plan, or null if no model could write a usable one */
	Written plan(Trip trip) {
		String request = fill(this.planRequest, trip).replace("{startDate}", trip.startDate().toString())
			.replace("{weekday}", weekday(trip.startDate()));
		AiClient.Answer answer = this.ai.generateJson(this.properties.models(), this.system, request,
				Itinerary.schema(), TEMPERATURE, PLAN_TOKENS);
		if (answer == null) {
			return null;
		}
		try {
			Itinerary itinerary = Itinerary.clean(this.json.readValue(answer.json(), Itinerary.class), trip.days());
			if (itinerary == null) {
				log.warn("Plan from {} not usable (missing days or parts)", answer.model());
				return null;
			}
			return new Written(itinerary, answer.model());
		}
		catch (RuntimeException ex) {
			log.warn("Plan from {} not readable: {}", answer.model(), ex.toString());
			return null;
		}
	}

	/**
	 * @param index the day to rewrite (0-based)
	 * @param wish what the traveller would like that day, or empty
	 * @return the new day, or null if no model could write a usable one
	 */
	Itinerary.Day day(Trip trip, Itinerary itinerary, int index, String wish) {
		LocalDate date = trip.startDate().plusDays(index);
		String others = IntStream.range(0, itinerary.days().size())
			.filter(i -> i != index)
			.mapToObj(i -> places(itinerary.days().get(i)))
			.collect(Collectors.joining("; "));
		String request = fill(this.dayRequest, trip).replace("{day}", String.valueOf(index + 1))
			.replace("{date}", date.toString())
			.replace("{weekday}", weekday(date))
			.replace("{otherDays}", others.isEmpty() ? "nothing yet" : others)
			.replace("{wish}", wish.isBlank() ? "none" : wish);
		AiClient.Answer answer = this.ai.generateJson(this.properties.models(), this.system, request,
				Itinerary.daySchema(), TEMPERATURE, DAY_TOKENS);
		if (answer == null) {
			return null;
		}
		try {
			return Itinerary.clean(this.json.readValue(answer.json(), Itinerary.Day.class));
		}
		catch (RuntimeException ex) {
			log.warn("Day from {} not readable: {}", answer.model(), ex.toString());
			return null;
		}
	}

	private static String fill(String template, Trip trip) {
		return template.replace("{place}", trip.place())
			.replace("{days}", String.valueOf(trip.days()))
			.replace("{travellers}", trip.travellers())
			.replace("{pace}", trip.pace())
			.replace("{budget}", trip.budget())
			.replace("{interests}", trip.interests().isEmpty() ? "a bit of everything" : String.join(", ", trip.interests()))
			.replace("{currency}", trip.currency());
	}

	private static String places(Itinerary.Day day) {
		return day.title() + " (" + day.morning().place() + ", " + day.afternoon().place() + ", " + day.evening().place()
				+ ")";
	}

	private static String weekday(LocalDate date) {
		return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
	}

	private static String prompt(String path) {
		try {
			return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
