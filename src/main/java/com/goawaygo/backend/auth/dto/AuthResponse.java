package com.goawaygo.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.goawaygo.backend.user.UserResponse;

/**
 * Successful sign-in / refresh.
 *
 * @param accessToken JWT to send as {@code Authorization: Bearer <accessToken>}
 * @param expiresIn access token lifetime in seconds
 * @param refreshToken only for mobile clients; web clients receive it as an HttpOnly cookie instead
 * @param accountRestored true when this sign-in cancelled the account's pending deletion (absent otherwise)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(UserResponse user, String accessToken, String tokenType, long expiresIn, String refreshToken,
		Boolean accountRestored) {
}
