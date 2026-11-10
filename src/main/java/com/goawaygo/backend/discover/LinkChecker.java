package com.goawaygo.backend.discover;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Opens every link the AI suggests before it is shown. AI models can invent web addresses; a link that doesn't
 * answer successfully (or not in time) is silently removed, the rest of its item stays.
 *
 * <p>The AI's answer depends on text people type (place names), so a link could be steered at the backend's own
 * network (server-side request forgery). Only {@code https} links to public addresses are opened, and redirects are
 * not followed (a redirect counts as an answer).
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
			.followRedirects(HttpClient.Redirect.NEVER)
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
			URI uri = URI.create(link);
			if (!isPublicHttps(uri)) {
				return CompletableFuture.completedFuture(false);
			}
			request = HttpRequest.newBuilder(uri)
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

	/** An {@code https} address on the default port whose host resolves only to public internet addresses. */
	static boolean isPublicHttps(URI uri) {
		String host = uri.getHost();
		if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || (uri.getPort() != -1 && uri.getPort() != 443)
				|| uri.getUserInfo() != null) {
			return false;
		}
		String name = host.toLowerCase(Locale.ROOT);
		if (name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".internal")
				|| name.endsWith(".local")) {
			return false;
		}
		try {
			InetAddress[] addresses = InetAddress.getAllByName(host);
			for (InetAddress address : addresses) {
				if (!isPublic(address)) {
					return false;
				}
			}
			return addresses.length > 0;
		}
		catch (UnknownHostException | SecurityException ex) {
			return false;
		}
	}

	private static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] b = address.getAddress();
		if (b.length == 4) {
			int first = b[0] & 0xff;
			int second = b[1] & 0xff;
			// 0.0.0.0/8, 100.64.0.0/10 (carrier-grade NAT), 192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4
			return first != 0 && !(first == 100 && second >= 64 && second <= 127)
					&& !(first == 192 && second == 0 && (b[2] & 0xff) == 0) && !(first == 198 && (second == 18 || second == 19))
					&& first < 240;
		}
		// IPv6 unique local addresses fc00::/7.
		return (b[0] & 0xfe) != 0xfc;
	}

}
