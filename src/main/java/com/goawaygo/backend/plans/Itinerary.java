package com.goawaygo.backend.plans;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A plan's itinerary, as the AI wrote it and as it is stored ({@code trip_plans.itinerary}). Costs are rough estimates
 * in the plan's currency for the whole group: {@link Day#cost()} covers that day's food, local transport and
 * activities; {@link #stayPerNight()} the stay. Flights are never included.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Itinerary(String title, String summary, List<Day> days, List<String> tips, long stayPerNight) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Day(String title, Slot morning, Slot afternoon, Slot evening, List<String> food, String tip,
			long cost) {
	}

	/** One part of a day: where, what to do there and its rough cost. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Slot(String place, String activity, long cost) {
	}

	static final int TITLE = 80;

	static final int SUMMARY = 400;

	static final int PLACE = 80;

	static final int ACTIVITY = 240;

	static final int FOOD = 80;

	static final int TIP = 200;

	static final int MAX_FOOD = 3;

	static final int MAX_TIPS = 5;

	/** Above this a cost is clearly wrong (and the field is left empty instead). */
	static final long MAX_COST = 100_000_000;

	/**
	 * The same itinerary cleaned up for showing and storing: texts trimmed and cut to length, lists capped, costs
	 * kept within sense. Null when it isn't a usable plan of {@code days} days.
	 */
	static Itinerary clean(Itinerary raw, int days) {
		if (raw == null || raw.days() == null || raw.days().size() < days || blank(raw.title())) {
			return null;
		}
		List<Day> cleaned = new ArrayList<>();
		for (Day day : raw.days().subList(0, days)) {
			Day ok = clean(day);
			if (ok == null) {
				return null;
			}
			cleaned.add(ok);
		}
		return new Itinerary(cut(raw.title(), TITLE), cut(raw.summary(), SUMMARY), cleaned,
				texts(raw.tips(), MAX_TIPS, TIP), cost(raw.stayPerNight()));
	}

	/** One day cleaned up, or null if a part of it is missing. */
	static Day clean(Day day) {
		if (day == null || blank(day.title())) {
			return null;
		}
		Slot morning = clean(day.morning());
		Slot afternoon = clean(day.afternoon());
		Slot evening = clean(day.evening());
		if (morning == null || afternoon == null || evening == null) {
			return null;
		}
		return new Day(cut(day.title(), TITLE), morning, afternoon, evening, texts(day.food(), MAX_FOOD, FOOD),
				cut(day.tip(), TIP), cost(day.cost()));
	}

	private static Slot clean(Slot slot) {
		if (slot == null || blank(slot.place()) || blank(slot.activity())) {
			return null;
		}
		return new Slot(cut(slot.place(), PLACE), cut(slot.activity(), ACTIVITY), cost(slot.cost()));
	}

	/** Every text in the plan, for the content check before it is shared. */
	String allText() {
		StringBuilder text = new StringBuilder(this.title).append('\n').append(this.summary);
		for (Day day : this.days) {
			text.append('\n').append(day.title());
			for (Slot slot : List.of(day.morning(), day.afternoon(), day.evening())) {
				text.append('\n').append(slot.place()).append(": ").append(slot.activity());
			}
			day.food().forEach(food -> text.append('\n').append(food));
			text.append('\n').append(day.tip());
		}
		this.tips.forEach(tip -> text.append('\n').append(tip));
		return text.toString();
	}

	Itinerary withDay(int index, Day day) {
		List<Day> changed = new ArrayList<>(this.days);
		changed.set(index, day);
		return new Itinerary(this.title, this.summary, List.copyOf(changed), this.tips, this.stayPerNight);
	}

	/** Gemini's {@code responseSchema} for a whole itinerary. */
	static Map<String, Object> schema() {
		return Map.of("type", "OBJECT", "properties",
				Map.of("title", string(), "summary", string(), "days", Map.of("type", "ARRAY", "items", daySchema()),
						"tips", Map.of("type", "ARRAY", "items", string()), "stayPerNight", integer()),
				"required", List.of("title", "summary", "days", "tips", "stayPerNight"));
	}

	/** Gemini's {@code responseSchema} for one day. */
	static Map<String, Object> daySchema() {
		Map<String, Object> slot = Map.of("type", "OBJECT", "properties",
				Map.of("place", string(), "activity", string(), "cost", integer()), "required",
				List.of("place", "activity", "cost"));
		return Map.of("type", "OBJECT", "properties",
				Map.of("title", string(), "morning", slot, "afternoon", slot, "evening", slot, "food",
						Map.of("type", "ARRAY", "items", string()), "tip", string(), "cost", integer()),
				"required", List.of("title", "morning", "afternoon", "evening", "food", "tip", "cost"));
	}

	private static Map<String, Object> string() {
		return Map.of("type", "STRING");
	}

	private static Map<String, Object> integer() {
		return Map.of("type", "INTEGER");
	}

	private static List<String> texts(List<String> values, int max, int length) {
		if (values == null) {
			return List.of();
		}
		return values.stream().filter(v -> !blank(v)).limit(max).map(v -> cut(v, length)).toList();
	}

	private static long cost(long value) {
		return (value < 0 || value > MAX_COST) ? 0 : value;
	}

	private static String cut(String value, int max) {
		String text = (value == null) ? "" : value.strip().replaceAll("\\s+", " ");
		return (text.length() <= max) ? text : text.substring(0, max - 1).strip() + "…";
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

}
