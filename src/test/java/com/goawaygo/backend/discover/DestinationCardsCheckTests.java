package com.goawaygo.backend.discover;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.goawaygo.backend.discover.DestinationCards.RawItem;

/** The checks every AI card goes through before it is shown. */
class DestinationCardsCheckTests {

	@Test
	void changingDetailsAreTaggedEvenWhenTheAiForgot() {
		DestinationCards.Card card = DestinationCards.check("currency", List.of(
				new RawItem("Exchange rate", "About 1 USD = 0.9 EUR", false, null),
				new RawItem("Metro fare", "A single ticket costs about €2", false, null),
				new RawItem("Opening hours", "Usually open 9:00 to 18:00", false, null),
				new RawItem("Tipping", "Service is included; rounding up is appreciated", false, null),
				new RawItem("Time zone", "UTC+5:30, no daylight saving", false, null)));

		assertThat(card.items()).extracting(DestinationCards.Item::mayChange)
			.containsExactly(true, true, true, false, false);
	}

	@Test
	void addressesInTheTextAreRemovedAndOnlyHttpsLinksKept() {
		DestinationCards.Card card = DestinationCards.check("visa", List.of(
				new RawItem("Check before you go", "See www.example.gov for details", false, "http://example.gov"),
				new RawItem("eVisa", "Apply online before travel", false, "https://indianvisaonline.gov.in")));

		assertThat(card.items().get(0).detail()).isEqualTo("See for details");
		assertThat(card.items().get(0).link()).isNull();
		assertThat(card.items().get(1).link()).isEqualTo("https://indianvisaonline.gov.in");
	}

	@Test
	void itemsBreakingTheLengthRulesAreDropped() {
		String tooLong = "word ".repeat(DestinationCards.MAX_DETAIL_WORDS + 1);
		DestinationCards.Card card = DestinationCards.check("cuisine", List.of(
				new RawItem("Croissant", tooLong, false, null),
				new RawItem("", "No label", false, null),
				new RawItem("Baguette", "Long French bread with a crisp crust", false, null)));

		assertThat(card.items()).extracting(DestinationCards.Item::label).containsExactly("Baguette");
		assertThat(card.available()).isTrue();
	}

	@Test
	void aCardWithNothingValidIsNotAvailable() {
		assertThat(DestinationCards.check("hotels", null).available()).isFalse();
		assertThat(DestinationCards.check("hotels", List.of()).available()).isFalse();
	}

}
