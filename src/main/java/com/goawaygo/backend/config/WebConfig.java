package com.goawaygo.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.goawaygo.backend.ratelimit.AuthRateLimitInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final AuthRateLimitInterceptor authRateLimitInterceptor;

	public WebConfig(AuthRateLimitInterceptor authRateLimitInterceptor) {
		this.authRateLimitInterceptor = authRateLimitInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this.authRateLimitInterceptor).addPathPatterns("/api/auth/**");
	}

}
