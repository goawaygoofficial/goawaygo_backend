package com.goawaygo.backend.discover;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** A signed-in user's last 5 searched places (the home banner). Searching a place again moves it to the front. */
@Repository
public class SearchHistory {

	static final int KEEP = 5;

	private final JdbcTemplate jdbc;

	public SearchHistory(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A searched place, and the tourist spot whose photo the banner shows for it. */
	public record Entry(Place place, String topSpot, Instant searchedAt) {
	}

	@Transactional
	public void record(long userId, Place place, String topSpot) {
		this.jdbc.update("""
				INSERT INTO search_history (user_id, place_id, name, type, city, region, country, country_code, lat, lon,
				                            top_spot, searched_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (user_id, place_id) DO UPDATE SET top_spot = EXCLUDED.top_spot, searched_at = now()
				""", userId, place.id(), place.name(), place.type(), place.city(), place.region(), place.country(),
				place.countryCode(), place.lat(), place.lon(), topSpot);
		this.jdbc.update("""
				DELETE FROM search_history WHERE user_id = ? AND id NOT IN (
				    SELECT id FROM search_history WHERE user_id = ? ORDER BY searched_at DESC, id DESC LIMIT ?)
				""", userId, userId, KEEP);
	}

	public List<Entry> latest(long userId) {
		return this.jdbc.query("""
				SELECT place_id, name, type, city, region, country, country_code, lat, lon, top_spot, searched_at
				FROM search_history WHERE user_id = ? ORDER BY searched_at DESC, id DESC LIMIT ?
				""", (rs, row) -> {
			String name = rs.getString("name");
			String city = rs.getString("city");
			String region = rs.getString("region");
			String country = rs.getString("country");
			// Each part once, compared whole ("France" isn't already in "Ile-de-France"), as in PlaceSearch.
			List<String> parts = new ArrayList<>(List.of(name));
			for (String part : List.of(city, region, country)) {
				if (!part.isBlank() && parts.stream().noneMatch(part::equalsIgnoreCase)) {
					parts.add(part);
				}
			}
			Place place = new Place(rs.getString("place_id"), name, rs.getString("type"), city, region, country,
					rs.getString("country_code"), rs.getDouble("lat"), rs.getDouble("lon"), String.join(", ", parts));
			return new Entry(place, rs.getString("top_spot"), rs.getTimestamp("searched_at").toInstant());
		}, userId, KEEP);
	}

}
