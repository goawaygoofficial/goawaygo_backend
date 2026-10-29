package com.goawaygo.backend.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code goawaygo.media.*}: where Wanderbook photos are stored (Cloudflare R2, S3 API) and shown from.
 *
 * @param endpoint R2's S3 endpoint, {@code https://<account-id>.r2.cloudflarestorage.com}
 * @param bucket the bucket (public through {@code publicBaseUrl})
 * @param publicBaseUrl where stored files are shown from, e.g. {@code https://media.goawaygo.com}
 * @param accessKeyId R2 access key (Secret Manager; empty locally: uploads are refused)
 * @param secretAccessKey its secret
 * @param maxPhotoBytes largest photo accepted (originals are kept as they are)
 * @param maxTotalBytes uploads stop when all stored photos together reach this (stays inside R2's free 10 GB)
 */
@Validated
@ConfigurationProperties("goawaygo.media")
public record MediaProperties(@NotBlank String endpoint, @NotBlank String bucket, @NotBlank String publicBaseUrl,
		String accessKeyId, String secretAccessKey, @Positive long maxPhotoBytes, @Positive long maxTotalBytes) {

	boolean configured() {
		return this.accessKeyId != null && !this.accessKeyId.isBlank() && this.secretAccessKey != null
				&& !this.secretAccessKey.isBlank();
	}

	public String publicUrl(String objectKey) {
		return this.publicBaseUrl.replaceAll("/+$", "") + "/" + objectKey;
	}

}
