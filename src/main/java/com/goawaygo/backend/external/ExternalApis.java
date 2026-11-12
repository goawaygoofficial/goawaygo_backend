package com.goawaygo.backend.external;

import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * One {@link ExternalApiClient} per provider in {@code goawaygo.external.providers}. Features get theirs with
 * {@code externalApis.client("opentripmap")}.
 */
@Component
public class ExternalApis {

	private static final Logger log = LoggerFactory.getLogger(ExternalApis.class);

	private final Map<String, ExternalApiClient> clients = new TreeMap<>();

	public ExternalApis(ExternalApiProperties properties, ExternalApiCache cache, ExternalApiUsage usage,
			ObjectMapper json) {
		properties.providers().forEach((name, config) -> {
			this.clients.put(name, new ExternalApiClient(name, config, cache, usage, json));
			if (config.dailyLimit() == null && config.monthlyLimit() == null) {
				log.warn("{} has no usage limit: set daily-limit or monthly-limit below its free tier", name);
			}
		});
	}

	/** @throws IllegalArgumentException if {@code name} isn't configured */
	public ExternalApiClient client(String name) {
		ExternalApiClient client = this.clients.get(name);
		if (client == null) {
			throw new IllegalArgumentException("No outside API '" + name + "' in goawaygo.external.providers");
		}
		return client;
	}

}
