package com.goawaygo.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs ({@code @Scheduled}). Cloud Run stops idle instances, so jobs run only while the backend is up and
 * must be safe to skip or repeat (e.g. deleting abandoned Wanderbook uploads).
 */
@Configuration
@EnableScheduling
class SchedulingConfig {

}
