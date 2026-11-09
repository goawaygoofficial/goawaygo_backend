package com.goawaygo.backend.user;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The countries and currencies a user can pick (ISO 3166 and ISO 4217, names in English), from the JDK's lists. Only
 * currencies some country uses today are offered.
 */
public final class Regions {

	public record Country(String code, String name, String currency) {
	}

	public record CurrencyOption(String code, String name, String symbol) {
	}

	public record All(List<Country> countries, List<CurrencyOption> currencies) {
	}

	private static final All ALL = load();

	private Regions() {
	}

	public static All all() {
		return ALL;
	}

	public static boolean isCountry(String code) {
		return code != null && ALL.countries().stream().anyMatch(c -> c.code().equals(code));
	}

	public static boolean isCurrency(String code) {
		return code != null && ALL.currencies().stream().anyMatch(c -> c.code().equals(code));
	}

	private static All load() {
		List<Country> countries = new ArrayList<>();
		Map<String, CurrencyOption> currencies = new TreeMap<>();
		for (String code : Locale.getISOCountries()) {
			Locale locale = new Locale.Builder().setRegion(code).build();
			String name = locale.getDisplayCountry(Locale.ENGLISH);
			String currency = null;
			try {
				Currency c = Currency.getInstance(locale);
				if (c != null) {
					currency = c.getCurrencyCode();
					currencies.putIfAbsent(currency, new CurrencyOption(currency, c.getDisplayName(Locale.ENGLISH),
							c.getSymbol(new Locale.Builder().setLanguage("en").setRegion(code).build())));
				}
			}
			catch (IllegalArgumentException ex) {
				// No currency for this territory (e.g. Antarctica).
			}
			countries.add(new Country(code, name, currency));
		}
		countries.sort(Comparator.comparing(Country::name));
		return new All(List.copyOf(countries), List.copyOf(currencies.values()));
	}

}
