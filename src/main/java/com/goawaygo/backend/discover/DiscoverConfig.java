package com.goawaygo.backend.discover;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.goawaygo.backend.external.TestFakes;

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
		// A test run's parallel work (AI cards, photos) uses the fake outside services too.
		executor.setTaskDecorator(TestFakes::carry);
		executor.initialize();
		return executor;
	}

}
