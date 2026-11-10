package com.goawaygo.backend.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.goawaygo.backend.common.error.JsonSecurityErrorHandler;

/**
 * Stateless API security: every request is authenticated by its {@code Authorization: Bearer} access token.
 * No server sessions, no login form, no HTTP Basic.
 *
 * <p>CSRF protection is off because authentication is a header the browser never sends automatically. The one
 * cookie (the web refresh token) is HttpOnly, SameSite=Strict and only sent to {@code /api/auth}, where it can
 * only mint tokens that are returned to an allowed origin (CORS).
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, JsonSecurityErrorHandler errors) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/signin", "/api/auth/google-signin",
						"/api/auth/google-signin/code", "/api/auth/refresh", "/api/auth/logout",
						"/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/verify-email",
						"/api/auth/confirm-email", "/api/auth/purge-deleted-accounts")
				.permitAll()
				.requestMatchers("/error")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/actuator/health")
				.permitAll()
				// Discover is public; a signed-in user's token is still read (their searches become recent searches).
				.requestMatchers(HttpMethod.GET, "/api/discover/places", "/api/discover/destination", "/api/discover/ideas")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/discover/photos")
				.permitAll()
				// The daily ideas run: only makes what is due, so an extra call does nothing (DiscoverController).
				.requestMatchers(HttpMethod.POST, "/api/discover/refresh-ideas")
				.permitAll()
				// A shared trip plan opens for anyone with its link; making plans needs an account.
				.requestMatchers(HttpMethod.GET, "/api/plans/shared/**")
				.permitAll()
				// The countries and currencies a user can pick (UserController).
				.requestMatchers(HttpMethod.GET, "/api/regions")
				.permitAll()
				// The daily trip-reminder run: only sends what is due, once (NotificationController).
				.requestMatchers(HttpMethod.POST, "/api/notifications/reminders")
				.permitAll()
				// Book is public: prices and partner links, no account needed.
				.requestMatchers(HttpMethod.GET, "/api/book/**")
				.permitAll()
				// Wanderbook can be viewed signed out (decided for 4.2); a token still shows the viewer's relationship.
				.requestMatchers(HttpMethod.GET, "/api/wanderbook/profiles/**", "/api/wanderbook/people",
						"/api/wanderbook/feed", "/api/wanderbook/posts/**")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(errors)
				.accessDeniedHandler(errors))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
		return http.build();
	}

	/** BCrypt by default; the "{bcrypt}" prefix in stored hashes allows upgrading the algorithm later. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.allowedOrigins());
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Client-Type", "Accept-Language"));
		cors.setAllowCredentials(true); // the web refresh cookie
		cors.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
