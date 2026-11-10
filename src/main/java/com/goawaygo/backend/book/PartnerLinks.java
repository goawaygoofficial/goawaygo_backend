package com.goawaygo.backend.book;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.goawaygo.backend.external.ExternalApiCache;
import com.goawaygo.backend.external.ExternalApis;

/**
 * Turns partner pages (an Aviasales search, a Trip.com hotel search, ...) into our affiliate links with Travelpayouts'
 * links API, so bookings are counted for us. A link is made once and reused. When a link can't be made (the partner
 * hasn't approved us yet, Travelpayouts is down) the plain page is used: the traveller still gets there, we just
 * don't earn on it.
 */
@Component
public class PartnerLinks {

	private static final Logger log = LoggerFactory.getLogger(PartnerLinks.class);

	/** Travelpayouts takes up to 10 links per request. */
	private static final int PER_REQUEST = 10;

	private static final Duration NOT_TRACKED_TTL = Duration.ofHours(1);

	private final ExternalApis apis;

	private final ExternalApiCache cache;

	private final BookProperties properties;

	public PartnerLinks(ExternalApis apis, ExternalApiCache cache, BookProperties properties) {
		this.apis = apis;
		this.cache = cache;
		this.properties = properties;
	}

	/**
	 * Affiliate links for {@code urls}, in the same order.
	 *
	 * @param channel {@code app} or {@code web}, kept with each click in Travelpayouts' statistics
	 */
	public List<String> track(List<String> urls, String channel) {
		Map<String, String> links = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		for (String url : urls) {
			Optional<ExternalApiCache.Entry> cached = this.cache.find(FlightPrices.PROVIDER, key(url, channel));
			if (cached.isPresent() && cached.get().fresh()) {
				links.put(url, cached.get().body());
			}
			else if (!missing.contains(url)) {
				missing.add(url);
			}
		}
		for (int from = 0; from < missing.size(); from += PER_REQUEST) {
			List<String> batch = missing.subList(from, Math.min(missing.size(), from + PER_REQUEST));
			links.putAll(create(batch, channel));
		}
		return urls.stream().map(url -> links.getOrDefault(url, url)).toList();
	}

	public String track(String url, String channel) {
		return track(List.of(url), channel).get(0);
	}

	private Map<String, String> create(List<String> urls, String channel) {
		Map<String, String> made = new LinkedHashMap<>();
		try {
			Request body = new Request(Long.parseLong(this.properties.projectId()),
					Long.parseLong(this.properties.marker()), false,
					urls.stream().map(url -> new Request.Link(url, channel)).toList());
			Response response = this.apis.client(FlightPrices.PROVIDER)
				.call((http, key) -> http.post()
					.uri("/links/v1/create")
					.header("X-Access-Token", key)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(Response.class));
			if (response != null && response.result() != null && response.result().links() != null) {
				for (Response.Link link : response.result().links()) {
					if ("success".equals(link.code()) && link.partnerUrl() != null && !link.partnerUrl().isBlank()) {
						made.put(link.url(), link.partnerUrl());
						this.cache.put(FlightPrices.PROVIDER, key(link.url(), channel), link.partnerUrl(),
								this.properties.linksCacheTtl());
					}
					else if (link.url() != null) {
						// Not approved by this partner yet: the plain page, asked again after an hour.
						log.warn("No affiliate link for a {} page: {}", host(link.url()), link.message());
						this.cache.put(FlightPrices.PROVIDER, key(link.url(), channel), link.url(), NOT_TRACKED_TTL);
					}
				}
			}
		}
		catch (RuntimeException ex) {
			log.warn("Affiliate links not made, plain pages used: {}", ex.toString());
		}
		return made;
	}

	private static String key(String url, String channel) {
		return "link:" + channel + ":" + url;
	}

	private static String host(String url) {
		int start = url.indexOf("://");
		int end = url.indexOf('/', start + 3);
		return (start < 0) ? "?" : url.substring(start + 3, (end < 0) ? url.length() : end);
	}

	record Request(long trs, long marker, boolean shorten, List<Link> links) {

		record Link(String url, @JsonProperty("sub_id") String subId) {
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(Result result) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Result(List<Link> links) {
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Link(String url, String code, String message, @JsonProperty("partner_url") String partnerUrl) {
		}

	}

}
