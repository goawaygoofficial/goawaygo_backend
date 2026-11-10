package com.goawaygo.backend.wanderbook;

/** Tidies text people type in Wanderbook (bios, captions, comments, place names, report details). */
final class UserText {

	private UserText() {
	}

	/** Trims, drops control characters except new lines, and allows at most one empty line in a row. */
	static String clean(String text) {
		return (text == null) ? "" : text.strip().replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replaceAll("\\n{3,}", "\n\n");
	}

}
