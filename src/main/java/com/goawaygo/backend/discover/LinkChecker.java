package com.goawaygo.backend.discover;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Opens every link the AI suggests before it is shown. AI models can invent web addresses; a link that doesn't
 * answer successfully (or not in time) is silently removed, the rest of its item stays.
 */
@Component
public class LinkChecker {

	private final Duration timeout;

	private final String userAgent;

	private final HttpClient http;

	public LinkChecker(DiscoverProperties properties) {
		this.timeout = properties.linkCheckTimeout();
		this.userAgent = properties.userAgent();
		this.http = HttpClient.newBuilder()
			.connectTimeout(this.timeout)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}

	/** The cards with links that didn't answer removed. All links are checked at the same time. */
	public List<DestinationCards.Card> keepWorkingLinks(List<DestinationCards.Card> cards) {
		Set<String> links = new LinkedHashSet<>();
		cards.forEach(card -> card.items().forEach(item -> {
			if (item.link() != null) {
				links.add(item.link());
			}
		}));
		if (links.isEmpty()) {
			return cards;
		}
		Map<String, CompletableFuture<Boolean>> checks = new HashMap<>();
		links.forEach(link -> checks.put(link, check(link)));
		try {
			CompletableFuture.allOf(checks.values().toArray(CompletableFuture[]::new))
				.get(this.timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
		}
		catch (Exception ex) {
			// Slow links count as broken; the finished ones are used below.
		}
		return cards.stream()
			.map(card -> new DestinationCards.Card(card.id(), card.available(), card.items()
				.stream()
				.map(item -> (item.link() == null || works(checks.get(item.link()))) ? item
						: new DestinationCards.Item(item.label(), item.detail(), item.mayChange(), null))
				.toList()))
			.toList();
	}

	private static boolean works(CompletableFuture<Boolean> check) {
		return check.isDone() && !check.isCompletedExceptionally() && Boolean.TRUE.equals(check.getNow(false));
	}

	CompletableFuture<Boolean> check(String link) {
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(URI.create(link))
				.timeout(this.timeout)
				.header("User-Agent", this.userAgent)
				.GET()
				.build();
		}
		catch (IllegalArgumentException ex) {
			return CompletableFuture.completedFuture(false);
		}
		// GET rather than HEAD: many official sites refuse HEAD. The body is discarded unread.
		return this.http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.thenApply(response -> response.statusCode() < 400)
			.exceptionally(ex -> false);
	}

}
