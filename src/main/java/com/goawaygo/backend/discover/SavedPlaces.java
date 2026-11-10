package com.goawaygo.backend.discover;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Places a user saved to My Trips. */
@Repository
public class SavedPlaces {

	/** Plenty for a beta; stops one account filling the table. */
	static final int MAX_PER_USER = 500;

	private final JdbcTemplate jdbc;

	public SavedPlaces(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param key what identifies the place: an idea's id or {@code place:<id>} for a destination
	 * @param category idea category (beach, mountain, ...), or null
	 */
	public record Saved(long id, String key, String name, String area, String country, double lat, double lon,
			String category, Instant savedAt) {
	}

	/** Saves the place (saving it again changes nothing); false if the user has too many saved places. */
	public boolean save(long userId, String key, String name, String area, String country, double lat, double lon,
			String category) {
		Integer count = this.jdbc.queryForObject("SELECT count(*) FROM saved_places WHERE user_id = ?", Integer.class,
				userId);
		if (count != null && count >= MAX_PER_USER) {
			return false;
		}
		this.jdbc.update("""
				INSERT INTO saved_places (user_id, place_key, name, area, country, lat, lon, category)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, place_key) DO NOTHING
				""", userId, key, name, area, country, lat, lon, category);
		return true;
	}

	public List<Saved> list(long userId) {
		return this.jdbc.query("""
				SELECT id, place_key, name, area, country, lat, lon, category, created_at FROM saved_places
				WHERE user_id = ? ORDER BY created_at DESC, id DESC
				""", (rs, row) -> new Saved(rs.getLong("id"), rs.getString("place_key"), rs.getString("name"),
				rs.getString("area"), rs.getString("country"), rs.getDouble("lat"), rs.getDouble("lon"),
				rs.getString("category"), rs.getTimestamp("created_at").toInstant()), userId);
	}

	/** Removes one of the user's saved places; false if there was no such place for this user. */
	public boolean remove(long userId, long id) {
		return this.jdbc.update("DELETE FROM saved_places WHERE user_id = ? AND id = ?", userId, id) > 0;
	}

}
