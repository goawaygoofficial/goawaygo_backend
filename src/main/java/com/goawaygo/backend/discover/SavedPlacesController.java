package com.goawaygo.backend.discover;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.goawaygo.backend.common.error.ApiException;

/** The signed-in user's saved places (My Trips). */
@RestController
@RequestMapping("/api/me/saved-places")
public class SavedPlacesController {

	private final SavedPlaces saved;

	public SavedPlacesController(SavedPlaces saved) {
		this.saved = saved;
	}

	@GetMapping
	public List<SavedPlaces.Saved> list(@AuthenticationPrincipal Jwt jwt) {
		return this.saved.list(userId(jwt));
	}

	/**
	 * @param key an idea's id, or {@code place:<placeId>} for a destination
	 */
	public record SaveRequest(@NotBlank @Size(max = 300) String key, @NotBlank @Size(max = 200) String name,
			@Size(max = 200) String area, @NotBlank @Size(max = 100) String country,
			@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
			@NotNull @DecimalMin("-180") @DecimalMax("180") Double lon, @Size(max = 30) String category) {
	}

	/** Saves a place (saving it again changes nothing). */
	@PostMapping
	public ResponseEntity<Void> save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveRequest body) {
		boolean saved = this.saved.save(userId(jwt), body.key(), body.name(), (body.area() != null) ? body.area() : "",
				body.country(), body.lat(), body.lon(), body.category());
		if (!saved) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_saved", "You have saved the maximum number of places.");
		}
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		if (!this.saved.remove(userId(jwt), id)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "That saved place doesn't exist.");
		}
		return ResponseEntity.noContent().build();
	}

	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
