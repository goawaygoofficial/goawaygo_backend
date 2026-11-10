package com.goawaygo.backend.common.i18n;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.i18n.LocaleContextHolder;

/**
 * The languages GoAwayGo speaks (4.7). The apps send the user's choice as {@code Accept-Language}; anything else
 * falls back to English.
 */
public enum Lang {

	EN("en", "English"), HI("hi", "Hindi"), ES("es", "Spanish"), FR("fr", "French"), DE("de", "German"),
	AR("ar", "Arabic"), JA("ja", "Japanese");

	private final String code;

	/** The language's English name, for AI prompts ("Write in Hindi"). */
	private final String englishName;

	Lang(String code, String englishName) {
		this.code = code;
		this.englishName = englishName;
	}

	public String code() {
		return this.code;
	}

	public String englishName() {
		return this.englishName;
	}

	public Locale locale() {
		return Locale.forLanguageTag(this.code);
	}

	public boolean isEnglish() {
		return this == EN;
	}

	/** {@code hi}, {@code hi-IN}, {@code HI} → Hindi; unknown, blank or null → null. */
	public static Lang of(String tag) {
		if (tag == null || tag.isBlank()) {
			return null;
		}
		String language = Locale.forLanguageTag(tag.strip().replace('_', '-')).getLanguage();
		for (Lang lang : values()) {
			if (lang.code.equals(language)) {
				return lang;
			}
		}
		return null;
	}

	/** {@link #of}, or English. */
	public static Lang orEnglish(String tag) {
		Lang lang = of(tag);
		return (lang != null) ? lang : EN;
	}

	/** The current request's language (its {@code Accept-Language}, first supported one), or English. */
	public static Lang current() {
		Locale locale = LocaleContextHolder.getLocale();
		Lang lang = of(locale.toLanguageTag());
		return (lang != null) ? lang : EN;
	}

	/** Before Spring MVC has set the locale (security filters): read the header directly. */
	public static Lang of(HttpServletRequest request) {
		var locales = request.getLocales();
		while (locales.hasMoreElements()) {
			Lang lang = of(locales.nextElement().toLanguageTag());
			if (lang != null) {
				return lang;
			}
		}
		return EN;
	}

}
