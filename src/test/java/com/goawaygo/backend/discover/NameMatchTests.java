package com.goawaygo.backend.discover;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameMatchTests {

	@Test
	void sameNameAcceptsTheSpotsOwnArticle() {
		assertThat(NameMatch.sameName("Eiffel Tower", "Eiffel Tower")).isTrue();
		assertThat(NameMatch.sameName("Notre-Dame Cathedral", "Notre-Dame de Paris")).isTrue();
		assertThat(NameMatch.sameName("Louvre Museum", "Louvre")).isTrue();
		assertThat(NameMatch.sameName("Musée d'Orsay", "Musee d'Orsay")).isTrue();
	}

	@Test
	void sameNameRejectsOtherThingsWithTheSameName() {
		assertThat(NameMatch.sameName("Musée d'Orsay", "Musée d'Orsay station")).isFalse();
		assertThat(NameMatch.sameName("Taj Mahal", "Taj Mahal (film)")).isFalse();
		assertThat(NameMatch.sameName("Old Town", "Old Town Square")).isFalse(); // only generic words shared
		assertThat(NameMatch.sameName("Eiffel Tower", "Tokyo Tower")).isFalse();
	}

	@Test
	void mentionsNeedsADistinctiveWordOfTheSpot() {
		assertThat(NameMatch.mentions("Hawa Mahal", "Two men riding horses past Hawa Mahal in Jaipur")).isTrue();
		assertThat(NameMatch.mentions("Nyhavn", "three people riding bicycles")).isFalse();
		assertThat(NameMatch.mentions("Golden Temple", "the golden temple at sunset")).isTrue();
		assertThat(NameMatch.mentions("Golden Temple", "a temple in the hills")).isFalse();
	}

}
