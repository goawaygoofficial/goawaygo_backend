package com.goaway.backend.config;

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

import com.goaway.backend.common.error.JsonSecurityErrorHandler;

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
						"/api/auth/google-signin/code", "/api/auth/refresh", "/api/auth/logout")
				.permitAll()
				.requestMatchers("/error")
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
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Client-Type"));
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
