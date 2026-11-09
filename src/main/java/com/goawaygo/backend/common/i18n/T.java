package com.goawaygo.backend.common.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translations of the text the backend shows to people (errors, the bell, pushes, emails). The English sentence in
 * the code is the key: {@code i18n/<code>.json} maps it to the translation. Placeholders are {@code {0}}, {@code {1}}
 * and so on, filled after translating. A sentence with no translation stays English, so a missing entry never
 * breaks anything.
 */
public final class T {

	private static final Map<Lang, Map<String, String>> TRANSLATIONS = load();

	private T() {
	}

	/** {@code template} in {@code lang} (English if there is no translation), with its placeholders filled. */
	public static String t(Lang lang, String template, Object... args) {
		String text = template;
		if (lang != null && !lang.isEnglish()) {
			text = TRANSLATIONS.get(lang).getOrDefault(template, template);
		}
		return fill(text, args);
	}

	/** In the current request's language. */
	public static String t(String template, Object... args) {
		return t(Lang.current(), template, args);
	}

	/** Whether {@code template} has a translation in {@code lang} (tests: every sentence must be translated). */
	public static boolean has(Lang lang, String template) {
		return lang.isEnglish() || TRANSLATIONS.get(lang).containsKey(template);
	}

	public static Map<String, String> all(Lang lang) {
		return TRANSLATIONS.getOrDefault(lang, Map.of());
	}

	public static String fill(String text, Object... args) {
		String filled = text;
		for (int i = 0; i < args.length; i++) {
			filled = filled.replace("{" + i + "}", String.valueOf(args[i]));
		}
		return filled;
	}

	private static Map<Lang, Map<String, String>> load() {
		JsonMapper json = JsonMapper.builder().build();
		Map<Lang, Map<String, String>> all = new EnumMap<>(Lang.class);
		for (Lang lang : Lang.values()) {
			if (lang.isEnglish()) {
				continue;
			}
			try (InputStream in = T.class.getClassLoader().getResourceAsStream("i18n/" + lang.code() + ".json")) {
				all.put(lang, (in == null) ? Map.of() : json.readValue(in, new TypeReference<Map<String, String>>() {
				}));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return all;
	}

}
