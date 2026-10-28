package com.goawaygo.backend.user;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A user as returned by the API. Multi-word fields are snake_case on the wire, as the frontends expect. */
public record UserResponse(
		long id,
		String name,
		String email,
		@JsonProperty("profile_picture") String profilePicture,
		String phone,
		@JsonProperty("email_verified") boolean emailVerified,
		@JsonProperty("is_admin") boolean isAdmin) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getProfilePictureUrl(),
				user.getPhone(), user.isEmailVerified(), user.isAdmin());
	}

}
