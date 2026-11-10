package com.goawaygo.backend.user;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.goawaygo.backend.common.i18n.Lang;

/**
 * The countries and currencies a user can pick (ISO 3166 and ISO 4217), from the JDK's lists, named in each of our
 * languages. Only currencies some country uses today are offered.
 */
public final class Regions {

	public record Country(String code, String name, String currency) {
	}

	public record CurrencyOption(String code, String name, String symbol) {
	}

	public record All(List<Country> countries, List<CurrencyOption> currencies) {
	}

	private static final Map<Lang, All> ALL = new EnumMap<>(Lang.class);

	static {
		for (Lang lang : Lang.values()) {
			ALL.put(lang, load(lang.locale()));
		}
	}

	private Regions() {
	}

	/** Named in {@code lang}, countries in its alphabetical order. */
	public static All all(Lang lang) {
		return ALL.get(lang);
	}

	public static boolean isCountry(String code) {
		return code != null && ALL.get(Lang.EN).countries().stream().anyMatch(c -> c.code().equals(code));
	}

	public static boolean isCurrency(String code) {
		return code != null && ALL.get(Lang.EN).currencies().stream().anyMatch(c -> c.code().equals(code));
	}

	private static All load(Locale language) {
		List<Country> countries = new ArrayList<>();
		Map<String, CurrencyOption> currencies = new TreeMap<>();
		for (String code : Locale.getISOCountries()) {
			Locale region = new Locale.Builder().setRegion(code).build();
			String name = region.getDisplayCountry(language);
			String currency = null;
			try {
				Currency c = Currency.getInstance(region);
				if (c != null) {
					currency = c.getCurrencyCode();
					currencies.putIfAbsent(currency, new CurrencyOption(currency, c.getDisplayName(language),
							c.getSymbol(new Locale.Builder().setLanguage("en").setRegion(code).build())));
				}
			}
			catch (IllegalArgumentException ex) {
				// No currency for this territory (e.g. Antarctica).
			}
			countries.add(new Country(code, name, currency));
		}
		Collator collator = Collator.getInstance(language);
		countries.sort(Comparator.comparing(Country::name, collator));
		return new All(List.copyOf(countries), List.copyOf(currencies.values()));
	}

}
