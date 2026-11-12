package com.goawaygo.backend.plans;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Saved trip plans ({@code trip_plans}). */
@Repository
class TripPlans {

	private static final String COLUMNS = """
			id, public_id, user_id, place_id, place_name, place_area, country, lat, lon, airport, start_date, days,
			travellers, pace, budget, interests, currency, itinerary::text AS itinerary, model, shared, created_at,
			updated_at, language""";

	private final JdbcTemplate jdbc;

	private final ObjectMapper json;

	TripPlans(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Where a plan is for (from a destination page or a saved place). */
	record Destination(String placeId, String name, String area, String country, double lat, double lon) {
	}

	record Stored(long id, String publicId, long userId, Destination destination, String airport, LocalDate startDate,
			int days, String travellers, String pace, String budget, List<String> interests, String currency,
			Itinerary itinerary, String model, boolean shared, Instant createdAt, Instant updatedAt, String language) {
	}

	int count(long userId) {
		Integer count = this.jdbc.queryForObject("SELECT count(*) FROM trip_plans WHERE user_id = ?", Integer.class,
				userId);
		return (count != null) ? count : 0;
	}

	/** Saves a new plan and returns its public id. */
	String insert(long userId, Destination destination, String airport, Planner.Trip trip, Planner.Written written) {
		return this.jdbc.queryForObject("""
				INSERT INTO trip_plans (user_id, place_id, place_name, place_area, country, lat, lon, airport,
				    start_date, days, travellers, pace, budget, interests, currency, itinerary, model, language)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
				RETURNING public_id
				""", String.class, userId, destination.placeId(), destination.name(), destination.area(),
				destination.country(), destination.lat(), destination.lon(), airport, trip.startDate(), trip.days(),
				trip.travellers(), trip.pace(), trip.budget(), String.join(",", trip.interests()), trip.currency(),
				this.json.writeValueAsString(written.itinerary()), written.model(), trip.language().code());
	}

	/** The user's plans, newest first. */
	List<Stored> list(long userId) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM trip_plans WHERE user_id = ? ORDER BY created_at DESC, id DESC",
				this::row, userId);
	}

	Optional<Stored> find(String publicId) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM trip_plans WHERE public_id = ?", this::row, publicId)
			.stream()
			.findFirst();
	}

	void updateItinerary(long id, Itinerary itinerary, String model) {
		this.jdbc.update("UPDATE trip_plans SET itinerary = ?::jsonb, model = ?, updated_at = now() WHERE id = ?",
				this.json.writeValueAsString(itinerary), model, id);
	}

	void setShared(long id, boolean shared) {
		this.jdbc.update("UPDATE trip_plans SET shared = ?, updated_at = now() WHERE id = ?", shared, id);
	}

	/** A shared plan, unless its owner's account waits to be deleted (hidden from others meanwhile). */
	Optional<Stored> findShared(String publicId) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM trip_plans WHERE public_id = ? AND shared"
				+ " AND user_id NOT IN (SELECT id FROM users WHERE deletion_requested_at IS NOT NULL)", this::row, publicId)
			.stream()
			.findFirst();
	}

	void delete(long id) {
		this.jdbc.update("DELETE FROM trip_plans WHERE id = ?", id);
	}

	private Stored row(ResultSet rs, int rowNum) throws SQLException {
		String interests = rs.getString("interests");
		return new Stored(rs.getLong("id"), rs.getString("public_id"), rs.getLong("user_id"),
				new Destination(rs.getString("place_id"), rs.getString("place_name"), rs.getString("place_area"),
						rs.getString("country"), rs.getDouble("lat"), rs.getDouble("lon")),
				rs.getString("airport"), rs.getObject("start_date", LocalDate.class), rs.getInt("days"),
				rs.getString("travellers"), rs.getString("pace"), rs.getString("budget"),
				interests.isEmpty() ? List.of() : Arrays.asList(interests.split(",")), rs.getString("currency"),
				this.json.readValue(rs.getString("itinerary"), Itinerary.class), rs.getString("model"),
				rs.getBoolean("shared"), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant(), rs.getString("language"));
	}

}
