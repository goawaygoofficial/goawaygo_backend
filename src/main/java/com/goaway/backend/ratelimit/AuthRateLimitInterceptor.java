package com.goaway.backend.ratelimit;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-client-IP limits on the public auth endpoints. A blocked request never reaches the controller; the
 * {@link RateLimitedException} is rendered by the global exception handler like any other API error.
 *
 * <p>The IP is the connection's remote address. {@code X-Forwarded-For} is deliberately not read here: clients can
 * set it to anything. Behind a reverse proxy (Cloud Run), {@code server.forward-headers-strategy=native} makes Tomcat
 * trust the header only from internal proxy addresses, so {@code getRemoteAddr()} returns the real client.
 */
@Component
public class AuthRateLimitInterceptor implements HandlerInterceptor {

	private final RateLimiter rateLimiter;

	private final Map<String, Rule> rulesByPath;

	public AuthRateLimitInterceptor(RateLimiter rateLimiter, RateLimitProperties limits) {
		this.rateLimiter = rateLimiter;
		this.rulesByPath = Map.of(
				"/api/auth/signin", new Rule("signin-ip", limits.signinPerIp()),
				"/api/auth/signup", new Rule("signup-ip", limits.signupPerIp()),
				"/api/auth/google-signin", new Rule("google-signin-ip", limits.googleSigninPerIp()),
				"/api/auth/google-signin/code", new Rule("google-signin-ip", limits.googleSigninPerIp()),
				"/api/auth/refresh", new Rule("refresh-ip", limits.refreshPerIp()),
				"/api/auth/forgot-password", new Rule("forgot-password-ip", limits.forgotPasswordPerIp()),
				"/api/auth/reset-password", new Rule("token-redeem-ip", limits.tokenRedeemPerIp()),
				"/api/auth/verify-email", new Rule("token-redeem-ip", limits.tokenRedeemPerIp()));
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!"POST".equals(request.getMethod())) {
			return true;
		}
		Rule rule = this.rulesByPath.get(request.getRequestURI());
		if (rule != null) {
			this.rateLimiter.consume(rule.name(), request.getRemoteAddr(), rule.limit());
		}
		return true;
	}

	private record Rule(String name, RateLimitProperties.Limit limit) {
	}

}
