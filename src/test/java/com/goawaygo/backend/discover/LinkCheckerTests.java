package com.goawaygo.backend.discover;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** AI links are only opened when they are https and on the public internet (no server-side request forgery). */
class LinkCheckerTests {

	@Test
	void onlyPublicHttpsLinksAreOpened() {
		assertThat(LinkChecker.isPublicHttps(URI.create("http://example.com/visa"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://localhost/visa"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://127.0.0.1/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://10.1.2.3/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://192.168.0.1/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://169.254.169.254/computeMetadata/v1/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://metadata.google.internal/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://100.64.1.1/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://[::1]/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://[fd00::1]/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://user:pass@8.8.8.8/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://8.8.8.8:8443/"))).isFalse();
		assertThat(LinkChecker.isPublicHttps(URI.create("https://8.8.8.8/"))).isTrue();
	}

}
