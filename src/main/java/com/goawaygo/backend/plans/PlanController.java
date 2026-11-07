package com.goawaygo.backend.plans;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.ratelimit.RateLimiter;

/**
 * The AI trip planner (4.4). Making and changing plans needs an account (and is limited per account: each costs an AI
 * request); a shared plan can be opened by anyone with its link.
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

	static final Set<String> TRAVELLERS = Set.of("solo", "couple", "family", "friends");

	static final Set<String> PACES = Set.of("relaxed", "balanced", "packed");

	static final Set<String> BUDGETS = Set.of("budget", "mid", "luxury");

	static final Set<String> INTERESTS = Set.of("culture", "history", "food", "nature", "adventure", "beaches",
			"nightlife", "shopping", "art", "relaxation");

	static final int MAX_DAYS = 10;

	private static final int MAX_DAYS_AHEAD = 365;

	/** Prices in Indian rupees for now (India first), like Book; every currency later (4.7). */
	private static final String CURRENCY = "INR";

	private final PlanService plans;

	private final RateLimiter rateLimiter;

	private final PlanProperties.Limits limits;

	private final Clock clock;

	public PlanController(PlanService plans, RateLimiter rateLimiter, PlanProperties properties, Clock clock) {
		this.plans = plans;
		this.rateLimiter = rateLimiter;
		this.limits = properties.limits();
		this.clock = clock;
	}

	/** Where the trip goes: a destination page's place or a saved place. */
	public record Place(@Size(max = 255) String placeId, @NotBlank @Size(max = 200) String name,
			@Size(max = 200) String area, @NotBlank @Size(max = 100) String country,
			@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
			@NotNull @DecimalMin("-180") @DecimalMax("180") Double lon) {
	}

	/**
	 * @param startDate {@code YYYY-MM-DD}
	 * @param days 1-10
	 * @param travellers solo, couple, family or friends
	 * @param pace relaxed, balanced or packed
	 * @param budget budget, mid or luxury
	 * @param interests up to 6 of {@link #INTERESTS}
	 */
	public record CreateRequest(@NotNull @Valid Place place, @NotBlank String startDate, int days,
			@NotBlank String travellers, @NotBlank String pace, @NotBlank String budget,
			@Size(max = 6) List<String> interests) {
	}

	public record DayRequest(@Size(max = 200) String wish) {
	}

	public record ShareRequest(boolean shared) {
	}

	/** Makes and saves a plan (usually 10-40 s: the AI writes it meanwhile). */
	@PostMapping
	public ResponseEntity<PlanService.PlanView> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateRequest body) {
		long userId = Long.parseLong(jwt.getSubject());
		LocalDate start = startDate(body.startDate());
		if (body.days() < 1 || body.days() > MAX_DAYS) {
			throw invalid("invalid_days", "Plan 1 to " + MAX_DAYS + " days.");
		}
		String travellers = choice(body.travellers(), TRAVELLERS, "travellers");
		String pace = choice(body.pace(), PACES, "pace");
		String budget = choice(body.budget(), BUDGETS, "budget");
		List<String> interests = (body.interests() == null) ? List.of()
				: body.interests().stream().map(i -> choice(i, INTERESTS, "interest")).distinct().toList();
		this.rateLimiter.consume("plans-create-user", String.valueOf(userId), this.limits.createPerUser());
		Place p = body.place();
		TripPlans.Destination destination = new TripPlans.Destination(blankToNull(p.placeId()), p.name().strip(),
				(p.area() == null) ? "" : p.area().strip(), p.country().strip(), p.lat(), p.lon());
		String where = destination.area().isBlank() ? destination.name() + ", " + destination.country()
				: destination.name() + ", " + destination.area() + ", " + destination.country();
		PlanService.PlanView plan = this.plans.create(userId, destination,
				new Planner.Trip(where, start, body.days(), travellers, pace, budget, interests, CURRENCY));
		return ResponseEntity.status(HttpStatus.CREATED).body(plan);
	}

	/** The user's plans (My Trips), newest first. */
	@GetMapping
	public List<PlanService.Summary> list(@AuthenticationPrincipal Jwt jwt) {
		return this.plans.list(Long.parseLong(jwt.getSubject()));
	}

	@GetMapping("/{id}")
	public PlanService.PlanView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return this.plans.get(Long.parseLong(jwt.getSubject()), id);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		this.plans.delete(Long.parseLong(jwt.getSubject()), id);
		return ResponseEntity.noContent().build();
	}

	/** Rewrites one day (1-based), optionally following the traveller's wish ("more street food"). */
	@PostMapping("/{id}/days/{day}")
	public PlanService.PlanView changeDay(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
			@PathVariable int day, @Valid @RequestBody(required = false) DayRequest body) {
		long userId = Long.parseLong(jwt.getSubject());
		this.rateLimiter.consume("plans-change-user", String.valueOf(userId), this.limits.changesPerUser());
		String wish = (body == null || body.wish() == null) ? "" : body.wish().strip().replaceAll("[\\p{Cntrl}]", " ");
		return this.plans.changeDay(userId, id, day, wish);
	}

	/** Shares the plan by link (read-only) or stops sharing it. */
	@PutMapping("/{id}/share")
	public PlanService.PlanView share(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
			@RequestBody ShareRequest body) {
		return this.plans.share(Long.parseLong(jwt.getSubject()), id, body.shared());
	}

	/** A shared plan, for anyone with the link. */
	@GetMapping("/shared/{id}")
	public PlanService.PlanView shared(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
			HttpServletRequest request) {
		this.rateLimiter.consume("plans-view-ip", request.getRemoteAddr(), this.limits.viewPerIp());
		return this.plans.shared(id, (jwt != null) ? Long.valueOf(jwt.getSubject()) : null);
	}

	private LocalDate startDate(String value) {
		LocalDate date;
		try {
			date = LocalDate.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw invalid("invalid_dates", "The start date must look like 2026-12-05.");
		}
		LocalDate today = LocalDate.now(this.clock);
		// A day of slack: it may already be tomorrow where the traveller is.
		if (date.isBefore(today.minusDays(1)) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
			throw invalid("invalid_dates", "Pick a start date from today to a year ahead.");
		}
		return date;
	}

	private static String choice(String value, Set<String> allowed, String what) {
		String choice = (value == null) ? "" : value.strip().toLowerCase(Locale.ROOT);
		if (!allowed.contains(choice)) {
			throw invalid("invalid_" + what, "Unknown " + what + ".");
		}
		return choice;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	private static ApiException invalid(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message);
	}

}
