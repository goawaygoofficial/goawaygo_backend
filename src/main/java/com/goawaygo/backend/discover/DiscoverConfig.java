package com.goawaygo.backend.discover;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Threads for the work a destination page does in parallel (AI cards, spot lookups, photos). */
@Configuration(proxyBeanMethods = false)
class DiscoverConfig {

	@Bean
	Executor discoverExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("discover-");
		executor.setCorePoolSize(16);
		executor.setMaxPoolSize(32);
		executor.setQueueCapacity(200);
		executor.initialize();
		return executor;
	}

}
