package com.goawaygo.backend.plans;

import com.goawaygo.backend.common.i18n.Lang;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.goawaygo.backend.book.Airports;
import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.moderation.ContentModeration;
import com.goawaygo.backend.notifications.NotificationService;

/**
 * The AI trip planner: plans are made by the AI, saved for their owner (My Trips), changed one day at a time and
 * optionally shared by link. Shared plans are checked by moderation first (anyone can open them).
 */
@Service
public class PlanService {

	private final Planner planner;

	private final TripPlans plans;

	private final Airports airports;

	private final ContentModeration moderation;

	private final PlanProperties properties;

	private final NotificationService notifications;

	private final PlanViews views;

	public PlanService(Planner planner, TripPlans plans, Airports airports, ContentModeration moderation,
			PlanProperties properties, NotificationService notifications, PlanViews views) {
		this.planner = planner;
		this.plans = plans;
		this.airports = airports;
		this.moderation = moderation;
		this.properties = properties;
		this.notifications = notifications;
		this.views = views;
	}

	/** A plan as shown: the trip, every day with its date, and the cost estimate worked out from the days. */
	public record PlanView(String id, boolean mine, boolean shared, Place place, String airport, LocalDate startDate,
			LocalDate endDate, int days, String travellers, String pace, String budget, List<String> interests,
			String currency, String title, String summary, List<DayView> dayPlans, List<String> tips,
			Estimate estimate, Instant createdAt) {
	}

	public record Place(String placeId, String name, String area, String country, double lat, double lon) {
	}

	public record DayView(int day, LocalDate date, String title, Itinerary.Slot morning, Itinerary.Slot afternoon,
			Itinerary.Slot evening, List<String> food, String tip, long cost) {
	}

	/**
	 * Rough costs for the whole group (flights never included).
	 *
	 * @param perDay average of the days' food, local transport and activities
	 * @param stayPerNight a typical stay at the plan's budget level
	 * @param nights the trip's nights ({@code days - 1})
	 * @param total all days plus all nights
	 */
	public record Estimate(long perDay, long stayPerNight, int nights, long total) {
	}

	/** A plan in My Trips. */
	public record Summary(String id, String title, String placeName, String country, LocalDate startDate, int days,
			boolean shared, Instant createdAt) {
	}

	public PlanView create(long userId, TripPlans.Destination destination, Planner.Trip trip) {
		if (this.plans.count(userId) >= this.properties.maxPerUser()) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_plans",
					"You have {0} plans. Delete one to make a new plan.", this.properties.maxPerUser());
		}
		Planner.Written written = this.planner.plan(trip);
		if (written == null) {
			throw busy();
		}
		String id = this.plans.insert(userId, destination, airport(destination), trip, written);
		return view(this.plans.find(id).orElseThrow(), userId);
	}

	public List<Summary> list(long userId) {
		return this.plans.list(userId)
			.stream()
			.map(p -> new Summary(p.publicId(), p.itinerary().title(), p.destination().name(), p.destination().country(),
					p.startDate(), p.days(), p.shared(), p.createdAt()))
			.toList();
	}

	/** The user's own plan (and into their Recently viewed). */
	public PlanView get(long userId, String id) {
		TripPlans.Stored plan = owned(userId, id);
		this.views.record(userId, plan.id());
		return view(plan, userId);
	}

	/** A shared plan, for anyone with its link (read-only; into a signed-in viewer's Recently viewed). */
	public PlanView shared(String id, Long viewerId) {
		TripPlans.Stored plan = this.plans.findShared(id).orElseThrow(PlanService::notFound);
		this.notifications.planOpened(plan.id(), plan.userId(), viewerId);
		if (viewerId != null) {
			this.views.record(viewerId, plan.id());
		}
		return view(plan, viewerId);
	}

	public void delete(long userId, String id) {
		this.plans.delete(owned(userId, id).id());
	}

	/**
	 * Rewrites one day.
	 *
	 * @param day 1-based
	 * @param wish what the traveller would like that day, or empty
	 */
	public PlanView changeDay(long userId, String id, int day, String wish) {
		TripPlans.Stored plan = owned(userId, id);
		if (day < 1 || day > plan.days()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_day", "This plan has days 1 to {0}.", plan.days());
		}
		Itinerary.Day changed = this.planner.day(trip(plan), plan.itinerary(), day - 1, wish);
		if (changed == null) {
			throw busy();
		}
		Itinerary itinerary = plan.itinerary().withDay(day - 1, changed);
		if (plan.shared()) {
			// Anyone with the link sees the change.
			this.moderation.checkText(itinerary.allText());
		}
		this.plans.updateItinerary(plan.id(), itinerary, plan.model());
		return view(this.plans.find(id).orElseThrow(), userId);
	}

	/** Shares the plan by link (after a content check) or stops sharing it. */
	public PlanView share(long userId, String id, boolean shared) {
		TripPlans.Stored plan = owned(userId, id);
		if (shared && !plan.shared()) {
			this.moderation.checkText(plan.itinerary().allText());
		}
		this.plans.setShared(plan.id(), shared);
		return view(this.plans.find(id).orElseThrow(), userId);
	}

	private TripPlans.Stored owned(long userId, String id) {
		return this.plans.find(id).filter(p -> p.userId() == userId).orElseThrow(PlanService::notFound);
	}

	/** The destination's city airport, for the Flights shortcut; null when none is found. */
	private String airport(TripPlans.Destination destination) {
		try {
			return this.airports.suggest(destination.name())
				.stream()
				.filter(a -> "city".equals(a.type()) && a.country().equalsIgnoreCase(destination.country()))
				.map(Airports.Airport::code)
				.findFirst()
				.orElse(null);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	static Planner.Trip trip(TripPlans.Stored plan) {
		TripPlans.Destination d = plan.destination();
		String place = d.area().isBlank() ? d.name() + ", " + d.country() : d.name() + ", " + d.area() + ", " + d.country();
		return new Planner.Trip(place, plan.startDate(), plan.days(), plan.travellers(), plan.pace(), plan.budget(),
				plan.interests(), plan.currency(), Lang.orEnglish(plan.language()));
	}

	private static PlanView view(TripPlans.Stored plan, Long viewerId) {
		Itinerary itinerary = plan.itinerary();
		List<DayView> days = new ArrayList<>();
		long dayCosts = 0;
		for (int i = 0; i < itinerary.days().size(); i++) {
			Itinerary.Day day = itinerary.days().get(i);
			dayCosts += day.cost();
			days.add(new DayView(i + 1, plan.startDate().plusDays(i), day.title(), day.morning(), day.afternoon(),
					day.evening(), day.food(), day.tip(), day.cost()));
		}
		int nights = Math.max(0, plan.days() - 1);
		Estimate estimate = new Estimate(Math.round((double) dayCosts / plan.days()), itinerary.stayPerNight(), nights,
				dayCosts + itinerary.stayPerNight() * nights);
		TripPlans.Destination d = plan.destination();
		return new PlanView(plan.publicId(), viewerId != null && viewerId == plan.userId(), plan.shared(),
				new Place(d.placeId(), d.name(), d.area(), d.country(), d.lat(), d.lon()), plan.airport(),
				plan.startDate(), plan.startDate().plusDays(plan.days() - 1L), plan.days(), plan.travellers(),
				plan.pace(), plan.budget(), plan.interests(), plan.currency().toUpperCase(Locale.ROOT),
				itinerary.title(), itinerary.summary(), days, itinerary.tips(), estimate, plan.createdAt());
	}

	private static ApiException busy() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "planner_busy",
				"Our trip planner is busy right now. Please try again in a minute.");
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "plan_not_found", "This plan doesn't exist or isn't shared.");
	}

}
