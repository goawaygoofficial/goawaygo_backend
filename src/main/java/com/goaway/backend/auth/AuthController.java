package com.goaway.backend.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.goaway.backend.auth.dto.AuthResponse;
import com.goaway.backend.auth.dto.ForgotPasswordRequest;
import com.goaway.backend.auth.dto.GoogleCodeRequest;
import com.goaway.backend.auth.dto.GoogleSigninRequest;
import com.goaway.backend.auth.dto.RefreshRequest;
import com.goaway.backend.auth.dto.ResetPasswordRequest;
import com.goaway.backend.auth.dto.SigninRequest;
import com.goaway.backend.auth.dto.SignupRequest;
import com.goaway.backend.auth.dto.VerifyEmailRequest;
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

	private final GoogleAuthorizationCodeExchanger googleCodeExchanger;

	private final AccountEmailService accountEmails;

	public AuthController(AuthService authService, AuthProperties properties,
			GoogleAuthorizationCodeExchanger googleCodeExchanger, AccountEmailService accountEmails) {
		this.authService = authService;
		this.properties = properties;
		this.googleCodeExchanger = googleCodeExchanger;
		this.accountEmails = accountEmails;
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

	@PostMapping("/google-signin")
	public ResponseEntity<AuthResponse> googleSignin(@Valid @RequestBody GoogleSigninRequest request,
			@RequestHeader(value = CLIENT_TYPE_HEADER, required = false) String clientType,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return respond(this.authService.googleSignin(request.idToken(), userAgent), isMobile(clientType));
	}

	/**
	 * Google sign-in via the authorization-code flow (the website's custom button). The code is exchanged for an ID
	 * token here, outside any database transaction, then handled exactly like {@link #googleSignin}.
	 */
	@PostMapping("/google-signin/code")
	public ResponseEntity<AuthResponse> googleSigninWithCode(@Valid @RequestBody GoogleCodeRequest request,
			@RequestHeader(value = CLIENT_TYPE_HEADER, required = false) String clientType,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		String idToken = this.googleCodeExchanger.exchangeForIdToken(request.code());
		return respond(this.authService.googleSignin(idToken, userAgent), isMobile(clientType));
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

	/**
	 * Always 202 with the same body, whether or not the account exists, and never faster than
	 * {@code goaway.auth.forgot-password-min-response}: the reset email is sent inside the request, so without the
	 * padding the response time would reveal whether an email went out.
	 */
	@PostMapping("/forgot-password")
	public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
		MinimumDuration.run(this.properties.forgotPasswordMinResponse(),
				() -> this.accountEmails.requestPasswordReset(request.email()));
		return ResponseEntity.accepted()
			.body(Map.of("message", "If an account exists for that email, we have sent a link to reset the password."));
	}

	@PostMapping("/reset-password")
	public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		this.accountEmails.resetPassword(request.token(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/verify-email")
	public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		this.accountEmails.verifyEmail(request.token());
		return ResponseEntity.noContent().build();
	}

	/** Signed-in users only. */
	@PostMapping("/resend-verification")
	public ResponseEntity<Void> resendVerification(@AuthenticationPrincipal Jwt jwt) {
		this.accountEmails.resendVerificationEmail(Long.parseLong(jwt.getSubject()));
		return ResponseEntity.accepted().build();
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
