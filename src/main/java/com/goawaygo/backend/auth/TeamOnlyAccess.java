package com.goawaygo.backend.auth;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.external.TestFakes;
import com.goawaygo.backend.user.User;

/**
 * Team-only sign-in for dev and test (MVP 2). With {@code goawaygo.team-only.emails} set (environment variable
 * {@code TEAM_ONLY_EMAILS}, a comma-separated list), only those accounts may sign up, sign in (password or Google) or
 * keep a session, and they are made admins. Everyone else gets 403 {@code team_only}. Automated and performance test
 * runs (requests with this environment's {@link TestFakes} token) are let through, so the tests keep their own
 * accounts. Empty (prod, local development): everyone may sign in, as before.
 */
@Component
public class TeamOnlyAccess {

	private static final Logger log = LoggerFactory.getLogger(TeamOnlyAccess.class);

	private final Set<String> teamEmails;

	public TeamOnlyAccess(@Value("${goawaygo.team-only.emails:}") List<String> emails) {
		this.teamEmails = (emails == null) ? Set.of()
				: emails.stream()
					.map(String::trim)
					.filter(e -> !e.isEmpty())
					.map(User::normalizeEmail)
					.collect(Collectors.toUnmodifiableSet());
		if (!this.teamEmails.isEmpty()) {
			log.info("Team-only sign-in: {} account(s) may sign in here", this.teamEmails.size());
		}
	}

	/** True when this environment is restricted to the team. */
	public boolean enabled() {
		return !this.teamEmails.isEmpty();
	}

	/** True for a team account in a restricted environment (it is made an admin). */
	public boolean isTeam(String email) {
		return enabled() && email != null && this.teamEmails.contains(User.normalizeEmail(email));
	}

	/** Refuses anyone but the team (and test runs) in a restricted environment. */
	public void check(String email) {
		if (!enabled() || TestFakes.active() || isTeam(email)) {
			return;
		}
		throw new ApiException(HttpStatus.FORBIDDEN, "team_only",
				"This GoAwayGo environment is only for the GoAwayGo team. Use goawaygo.com.");
	}

}
