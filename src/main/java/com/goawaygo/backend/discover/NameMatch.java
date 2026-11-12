package com.goawaygo.backend.discover;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a photo really shows the spot asked for, by comparing names: "Notre-Dame Cathedral" matches the
 * article "Notre-Dame de Paris" but "Musée d'Orsay" does not match "Musée d'Orsay station".
 */
final class NameMatch {

	/** Small words that say nothing about which place it is. */
	private static final Set<String> STOP = Set.of("the", "of", "and", "in", "at", "on", "a", "an", "de", "la", "le",
			"les", "du", "des", "di", "del", "della", "el", "al", "da", "do", "das", "dos", "von", "van");

	/** Words that turn a place's name into something else (its station, a film named after it, ...). */
	private static final Set<String> OTHER_THING = Set.of("station", "metro", "railway", "line", "airport", "film",
			"album", "song", "novel", "band", "episode", "ship", "game", "footballer", "constituency", "district",
			"municipality", "disambiguation", "bus", "stop", "tram", "hotel");

	/** Words many places share; a match needs at least one word that isn't one of these. */
	private static final Set<String> GENERIC = Set.of("temple", "beach", "mount", "mountain", "lake", "national",
			"park", "museum", "palace", "fort", "fortress", "church", "cathedral", "mosque", "tower", "bridge", "island",
			"islands", "river", "falls", "waterfall", "old", "town", "city", "square", "street", "market", "garden",
			"gardens", "castle", "hill", "hills", "bay", "valley", "monument", "memorial", "gate", "basilica", "shrine",
			"hall", "house", "art", "great", "new", "north", "south", "east", "west", "saint", "st");

	private NameMatch() {
	}

	static List<String> tokens(String text) {
		String plain = Normalizer.normalize((text == null) ? "" : text, Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT);
		return Arrays.stream(plain.split("[^\\p{L}\\p{N}]+")).filter(t -> !t.isBlank() && !STOP.contains(t)).toList();
	}

	/** True if an article or photo titled {@code title} is about {@code spot}. */
	static boolean sameName(String spot, String title) {
		List<String> spotTokens = tokens(spot);
		List<String> titleTokens = tokens(title);
		if (spotTokens.isEmpty() || titleTokens.isEmpty()) {
			return false;
		}
		for (String token : titleTokens) {
			if (OTHER_THING.contains(token) && !spotTokens.contains(token)) {
				return false;
			}
		}
		long shared = spotTokens.stream().filter(titleTokens::contains).count();
		boolean distinctiveShared = spotTokens.stream().anyMatch(t -> !GENERIC.contains(t) && titleTokens.contains(t));
		// Most of the spot's words are in the title ("Notre-Dame Cathedral" / "Notre-Dame de Paris"), or the title is
		// a shorter form of the spot's name ("Louvre Museum" / "Louvre").
		boolean titleIsShortForm = spotTokens.containsAll(titleTokens);
		return distinctiveShared && (shared * 3 >= spotTokens.size() * 2 || titleIsShortForm);
	}

	/** True if a photo description mentions the spot: one of its distinctive words, or its whole name. */
	static boolean mentions(String spot, String description) {
		if (description == null || description.isBlank()) {
			return false;
		}
		List<String> text = tokens(description);
		List<String> spotTokens = tokens(spot);
		List<String> distinctive = spotTokens.stream().filter(t -> !GENERIC.contains(t) && t.length() >= 4).toList();
		if (!distinctive.isEmpty()) {
			return distinctive.stream().anyMatch(text::contains);
		}
		return !spotTokens.isEmpty() && String.join(" ", text).contains(String.join(" ", spotTokens));
	}

	/** True if two spot names are about the same thing (to avoid showing it twice). */
	static boolean similar(String a, String b) {
		return sameName(a, b) || sameName(b, a);
	}

}
