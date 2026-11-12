package com.goawaygo.backend.plans;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Trip plans a signed-in user opened, their own and shared ones (Recently viewed). Opening one again moves it to the
 * front; only the latest {@link #KEEP} are kept. Plans deleted or no longer shared drop out.
 */
@Repository
public class PlanViews {

	public static final int KEEP = 30;

	private final JdbcTemplate jdbc;

	public PlanViews(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @param mine the viewer's own plan (otherwise one shared with them by link) */
	public record Viewed(String id, String title, String placeName, String country, LocalDate startDate, int days,
			boolean mine, Instant viewedAt) {
	}

	void record(long userId, long planId) {
		this.jdbc.update("""
				INSERT INTO plan_views (user_id, plan_id) VALUES (?, ?)
				ON CONFLICT (user_id, plan_id) DO UPDATE SET viewed_at = now()""", userId, planId);
		this.jdbc.update("""
				DELETE FROM plan_views WHERE user_id = ? AND plan_id NOT IN (
				    SELECT plan_id FROM plan_views WHERE user_id = ? ORDER BY viewed_at DESC LIMIT ?)""", userId, userId,
				KEEP);
	}

	/** Newest first: the viewer's own plans, and other people's while still shared (by an account not leaving). */
	public List<Viewed> latest(long userId) {
		return this.jdbc.query("""
				SELECT t.public_id, t.itinerary ->> 'title' AS title, t.place_name, t.country, t.start_date, t.days,
				       t.user_id = v.user_id AS mine, v.viewed_at
				FROM plan_views v JOIN trip_plans t ON t.id = v.plan_id JOIN users owner ON owner.id = t.user_id
				WHERE v.user_id = ?
				  AND (t.user_id = v.user_id OR (t.shared AND owner.deletion_requested_at IS NULL))
				ORDER BY v.viewed_at DESC LIMIT ?""",
				(rs, row) -> new Viewed(rs.getString("public_id"), rs.getString("title"), rs.getString("place_name"),
						rs.getString("country"), rs.getObject("start_date", LocalDate.class), rs.getInt("days"),
						rs.getBoolean("mine"), rs.getTimestamp("viewed_at").toInstant()),
				userId, KEEP);
	}

	public void clear(long userId) {
		this.jdbc.update("DELETE FROM plan_views WHERE user_id = ?", userId);
	}

}
