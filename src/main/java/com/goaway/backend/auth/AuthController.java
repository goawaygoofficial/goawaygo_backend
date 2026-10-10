package com.goaway.backend.auth;

import java.time.Duration;
import java.time.Instant;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.goaway.backend.auth.dto.AuthResponse;
import com.goaway.backend.auth.dto.RefreshRequest;
import com.goaway.backend.auth.dto.SigninRequest;
import com.goaway.backend.auth.dto.SignupRequest;
import com.goaway.backend.user.UserResponse;

/**
 * Sign-up, sign-in, token refresh and logout.
 *
 * <p>Where the refresh token goes depends on the client:
 * <ul>
 * <li>Mobile app (sends {@code X-Client-Type: mobile}): in the JSON body; the app keeps it in secure storage.</li>
 * <li>Web (everything else): only in an HttpOnly, Secure, SameSite=Strict cookie scoped to {@code /api/auth},
 * so page JavaScript can never read it.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	static final String CLIENT_TYPE_HEADER = "X-Client-Type";

	private static final String MOBILE = "mobile";

	private final AuthService authService;

	private final AuthProperties properties;

	public AuthController(AuthService authService, AuthProperties properties) {
		this.authService = authService;
		this.properties = properties;
	}

	@PostMapping("/signup")
	public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.authService.signup(request));
	}

	@PostMapping("/signin")
	public ResponseEntity<AuthResponse> signin(@Valid @RequestBody SigninRequest request,
			@RequestHeader(value = CLIENT_TYPE_HEADER, required = false) String clientType,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return respond(this.authService.signin(request, userAgent), isMobile(clientType));
	}

	@PostMapping("/refresh")
	public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) RefreshRequest body,
			@CookieValue(name = "${goaway.auth.refresh-cookie.name}", required = false) String cookieToken,
			@RequestHeader(value = CLIENT_TYPE_HEADER, required = false) String clientType,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return respond(this.authService.refresh(tokenFrom(body, cookieToken), userAgent), isMobile(clientType));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body,
			@CookieValue(name = "${goaway.auth.refresh-cookie.name}", required = false) String cookieToken) {
		this.authService.logout(tokenFrom(body, cookieToken));
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO)).build();
	}

	private ResponseEntity<AuthResponse> respond(AuthService.Session session, boolean mobile) {
		var refresh = session.refreshToken();
		var body = new AuthResponse(session.user(), session.accessToken(), "Bearer", session.expiresIn(),
				mobile ? refresh.value() : null);
		var response = ResponseEntity.ok();
		if (!mobile) {
			Duration maxAge = Duration.between(Instant.now(), refresh.expiresAt());
			response.header(HttpHeaders.SET_COOKIE, refreshCookie(refresh.value(), maxAge));
		}
		return response.body(body);
	}

	private String refreshCookie(String value, Duration maxAge) {
		var cookie = this.properties.refreshCookie();
		return ResponseCookie.from(cookie.name(), value)
			.httpOnly(true)
			.secure(cookie.secure())
			.sameSite(cookie.sameSite())
			.path(cookie.path())
			.maxAge(maxAge)
			.build()
			.toString();
	}

	private static String tokenFrom(RefreshRequest body, String cookieToken) {
		if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
			return body.refreshToken();
		}
		return cookieToken;
	}

	private static boolean isMobile(String clientType) {
		return MOBILE.equalsIgnoreCase(clientType);
	}

}
