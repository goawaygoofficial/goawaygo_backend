package com.goawaygo.backend.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserRepository users;

	public UserController(UserRepository users) {
		this.users = users;
	}

	/** The signed-in user's own profile. Users can only ever read their own record through this endpoint. */
	@GetMapping("/me")
	@Transactional(readOnly = true)
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		long userId = Long.parseLong(jwt.getSubject());
		return this.users.findById(userId)
			.map(UserResponse::from)
			// Token is valid but the account is gone (deleted): treat as signed out.
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "You need to sign in to do this."));
	}

}
