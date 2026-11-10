package com.goawaygo.backend.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

import com.goawaygo.backend.auth.dto.AuthResponse;
import com.goawaygo.backend.auth.dto.ForgotPasswordRequest;
import com.goawaygo.backend.auth.dto.GoogleCodeRequest;
import com.goawaygo.backend.auth.dto.GoogleSigninRequest;
import com.goawaygo.backend.auth.dto.RefreshRequest;
import com.goawaygo.backend.auth.dto.ResetPasswordRequest;
import com.goawaygo.backend.auth.dto.SigninRequest;
import com.goawaygo.backend.auth.dto.SignupRequest;
import com.goawaygo.backend.auth.dto.VerifyEmailRequest;
import com.goawaygo.backend.ratelimit.RateLimitProperties;
import com.goawaygo.backend.ratelimit.RateLimiter;
import com.goawaygo.backend.user.UserResponse;

/**
 * Sign-up, sign-in, token refresh and logout; changing the password or email and deleting the account (4.6).
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

	/**
	 * The daily deletion run, per caller: the daily job plus a few manual runs. Per caller, so nobody else can use
	 * up the job's runs.
	 */
	private static final RateLimitProperties.Limit PURGE_RUNS = new RateLimitProperties.Limit(6, Duration.ofHours(1));

	private final AccountEmailService accountEmails;

	private final AccountService accounts;

	private final RateLimiter rateLimiter;

	public AuthController(AuthService authService, AuthProperties properties,
			GoogleAuthorizationCodeExchanger googleCodeExchanger, AccountEmailService accountEmails,
			AccountService accounts, RateLimiter rateLimiter) {
		this.authService = authService;
		this.properties = properties;
		this.googleCodeExchanger = googleCodeExchanger;
		this.accountEmails = accountEmails;
		this.accounts = accounts;
		this.rateLimiter = rateLimiter;
	}

	/**
	 * "Confirm it's you" for the account changes below: the current password, or Google (an ID token from the app,
	 * or the website's authorization code).
	 */
	public record ConfirmRequest(String password, String googleIdToken, String googleCode) {
	}

	public record ChangePasswordRequest(String password, String googleIdToken, String googleCode,
			// 72 is BCrypt's input limit.
			@NotBlank(message = "Password is required.")
			@Size(min = 8, max = 72, message = "Password must be 8 to 72 characters.") String newPassword) {
	}

	public record ChangeEmailRequest(String password, String googleIdToken, String googleCode,
			@NotBlank(message = "Email is required.") @Email(message = "Enter a valid email address.")
			@Size(max = 320, message = "Email is too long.") String newEmail) {
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
			@CookieValue(name = "${goawaygo.auth.refresh-cookie.name}", required = false) String cookieToken,
			@RequestHeader(value = CLIENT_TYPE_HEADER, required = false) String clientType,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return respond(this.authService.refresh(tokenFrom(body, cookieToken), userAgent), isMobile(clientType));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body,
			@CookieValue(name = "${goawaygo.auth.refresh-cookie.name}", required = false) String cookieToken) {
		this.authService.logout(tokenFrom(body, cookieToken));
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO)).build();
	}

	/**
	 * Always 202 with the same body, whether or not the account exists, and never faster than
	 * {@code goawaygo.auth.forgot-password-min-response}: the reset email is sent inside the request, so without the
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

	/** Signed in. Signs out every device and returns a new session for this one. */
	@PostMapping("/change-password")
	public ResponseEntity<AuthResponse> changePassword(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody ChangePasswordRequest request,
			@RequestHeader(value = CLIENT_TYPE_HEADER, required = false) String clientType,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		AccountService.Proof proof = proof(request.password(), request.googleIdToken(), request.googleCode());
		return respond(this.accounts.changePassword(userId(jwt), proof, request.newPassword(), userAgent),
				isMobile(clientType));
	}

	/** Signed in. Emails a confirmation link to the new address ({@code pending_email} until it is opened). */
	@PostMapping("/change-email")
	public UserResponse changeEmail(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeEmailRequest request) {
		AccountService.Proof proof = proof(request.password(), request.googleIdToken(), request.googleCode());
		return this.accounts.requestEmailChange(userId(jwt), proof, request.newEmail());
	}

	/** The link in the new address's email. */
	@PostMapping("/confirm-email")
	public ResponseEntity<Void> confirmEmail(@Valid @RequestBody VerifyEmailRequest request) {
		this.accounts.confirmEmailChange(request.token());
		return ResponseEntity.noContent().build();
	}

	/** Signed in. Deleted for good after 30 days; signing in before then keeps the account. Signs out everywhere. */
	@PostMapping("/delete-account")
	public ResponseEntity<Map<String, Instant>> deleteAccount(@AuthenticationPrincipal Jwt jwt,
			@RequestBody(required = false) ConfirmRequest request) {
		AccountService.Proof proof = (request == null) ? null
				: proof(request.password(), request.googleIdToken(), request.googleCode());
		Instant deleteOn = this.accounts.requestDeletion(userId(jwt), proof);
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO))
			.body(Map.of("deleteOn", deleteOn));
	}

	/**
	 * Public: deletes the accounts whose 30 days are over, and nothing else, so calling it again does nothing. The
	 * daily GitHub Actions job calls it (Cloud Run gives the backend no CPU between requests).
	 */
	@PostMapping("/purge-deleted-accounts")
	public Map<String, Integer> purgeDeletedAccounts(HttpServletRequest request) {
		this.rateLimiter.consume("account-purge", request.getRemoteAddr(), PURGE_RUNS);
		return Map.of("deleted", this.accounts.purgeDue());
	}

	/** Exchanges the website's Google code outside any transaction, like sign-in. */
	private AccountService.Proof proof(String password, String googleIdToken, String googleCode) {
		String idToken = (googleCode != null && !googleCode.isBlank())
				? this.googleCodeExchanger.exchangeForIdToken(googleCode) : googleIdToken;
		return new AccountService.Proof(password, idToken);
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	private ResponseEntity<AuthResponse> respond(AuthService.Session session, boolean mobile) {
		var refresh = session.refreshToken();
		var body = new AuthResponse(session.user(), session.accessToken(), "Bearer", session.expiresIn(),
				mobile ? refresh.value() : null, session.restored() ? Boolean.TRUE : null);
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
