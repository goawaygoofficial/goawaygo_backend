package com.goawaygo.backend.user;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A user as returned by the API. Multi-word fields are snake_case on the wire, as the frontends expect.
 *
 * @param hasPassword whether the account has a password (otherwise "confirm it's you" uses Google)
 * @param googleLinked whether the account signs in with Google
 * @param pendingEmail a new email waiting for its confirmation link, or null
 * @param language the language GoAwayGo speaks to the user (en, hi, es, fr, de, ar, ja)
 */
public record UserResponse(
		long id,
		String name,
		String email,
		@JsonProperty("profile_picture") String profilePicture,
		String phone,
		@JsonProperty("email_verified") boolean emailVerified,
		@JsonProperty("is_admin") boolean isAdmin,
		String country,
		String currency,
		@JsonProperty("has_password") boolean hasPassword,
		@JsonProperty("google_linked") boolean googleLinked,
		@JsonProperty("pending_email") String pendingEmail,
		String language) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getProfilePictureUrl(),
				user.getPhone(), user.isEmailVerified(), user.isAdmin(), user.getCountry(), user.getCurrency(),
				user.getPasswordHash() != null, user.getGoogleSubject() != null, user.getPendingEmail(),
				user.getLanguage());
	}

}
