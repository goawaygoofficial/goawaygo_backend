package com.goawaygo.backend.external;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a fake AI answer that fits a Gemini {@code responseSchema}: every property filled, arrays with a few items,
 * texts that say they are test data. Used by {@link FakeHttp} for test runs, so plans, destination cards and trip
 * ideas work without calling the AI.
 */
final class SchemaFaker {

	/** Items per array. Plans need as many days as the trip has: the planner keeps the first ones it needs. */
	static final int ITEMS = 3;

	static final int DAYS = 30;

	private SchemaFaker() {
	}

	@SuppressWarnings("unchecked")
	static Object fake(Map<String, Object> schema, String name, int index) {
		if (schema == null) {
			return text(name, index);
		}
		String type = String.valueOf(schema.getOrDefault("type", "STRING")).toUpperCase(Locale.ROOT);
		return switch (type) {
			case "OBJECT" -> {
				Map<String, Object> object = new LinkedHashMap<>();
				Object properties = schema.get("properties");
				if (properties instanceof Map<?, ?> props) {
					props.forEach((key, value) -> object.put(String.valueOf(key),
							fake((value instanceof Map<?, ?> m) ? (Map<String, Object>) m : null, String.valueOf(key),
									index)));
				}
				yield object;
			}
			case "ARRAY" -> {
				Map<String, Object> items = (schema.get("items") instanceof Map<?, ?> m) ? (Map<String, Object>) m
						: null;
				int count = "days".equals(name) ? DAYS : ITEMS;
				List<Object> list = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					list.add(fake(items, singular(name), i));
				}
				yield list;
			}
			case "INTEGER" -> 100L * (index + 1);
			case "NUMBER" -> 1.5 * (index + 1);
			case "BOOLEAN" -> false;
			default -> (schema.get("enum") instanceof List<?> values && !values.isEmpty())
					? String.valueOf(values.get(index % values.size())) : text(name, index);
		};
	}

	private static String text(String name, int index) {
		String lower = (name != null) ? name.toLowerCase(Locale.ROOT) : "";
		if (lower.contains("url") || lower.contains("link")) {
			return "https://example.com/";
		}
		return "Test " + ((name != null && !name.isBlank()) ? name : "text") + " " + (index + 1);
	}

	private static String singular(String name) {
		if (name == null) {
			return "item";
		}
		return (name.endsWith("s") && name.length() > 1) ? name.substring(0, name.length() - 1) : name;
	}

}
