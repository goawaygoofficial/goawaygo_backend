package com.goaway.backend.auth.dto;

/** Body for refresh/logout from mobile clients. Web clients send the token in the HttpOnly cookie instead. */
public record RefreshRequest(String refreshToken) {
}
