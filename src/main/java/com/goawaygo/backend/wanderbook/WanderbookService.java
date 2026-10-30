package com.goawaygo.backend.wanderbook;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.goawaygo.backend.common.error.ApiException;
import com.goawaygo.backend.wanderbook.WanderbookRepository.ListedProfile;
import com.goawaygo.backend.wanderbook.WanderbookRepository.ProfileRow;

/**
 * Wanderbook profiles and who sees what (decided for 4.2):
 * <ul>
 * <li>Anyone, signed in or not, can view public profiles and their followers/following.</li>
 * <li>A private profile shows its name, photo, bio and counts to everyone; its posts and lists only to approved
 * followers. Following it sends a request the owner approves or declines. Making a profile public approves the
 * waiting requests.</li>
 * <li>Blocking hides two people from each other (the blocked person can't find the blocker's profile) and removes
 * follows both ways.</li>
 * </ul>
 */
@Service
public class WanderbookService {

	static final int PAGE_SIZE = 30;

	static final int SEARCH_RESULTS = 20;

	private final WanderbookRepository repository;

	private final PostService posts;

	public WanderbookService(WanderbookRepository repository, PostService posts) {
		this.repository = repository;
		this.posts = posts;
	}

	/** Viewer's relation to a profile. */
	public enum Relationship {

		SELF, FOLLOWING, REQUESTED, NONE;

		@JsonValue
		String json() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	/** A person in a list or search result. */
	public record Person(String publicId, String name, String photoUrl, String homeTown, boolean isPrivate) {

		static Person of(ProfileRow row) {
			return new Person(row.publicId(), row.name(), row.photoUrl(), row.homeTown(), row.isPrivate());
		}

	}

	/**
	 * A profile as the viewer sees it. {@code canSeePosts}: false for a private profile the viewer doesn't follow
	 * (and for someone the viewer blocked). {@code followRequests}: waiting requests, only on the viewer's own
	 * profile.
	 */
	public record Profile(String publicId, String name, String photoUrl, String coverUrl, String bio,
			String homeTown, boolean isPrivate, long posts, long followers, long following, Relationship relationship,
			boolean followsYou, boolean blockedByYou, boolean canSeePosts, Long followRequests) {
	}

	public record Page<T>(List<T> items, Long nextCursor) {
	}

	public Profile me(long userId) {
		return view(profileOf(userId), userId);
	}

	/** @param viewerId the signed-in viewer, or null */
	public Profile profile(String publicId, Long viewerId) {
		return view(visible(publicId, viewerId), viewerId);
	}

	@Transactional
	public Profile update(long userId, String bio, String homeTown, boolean isPrivate) {
		ProfileRow before = profileOf(userId);
		this.repository.update(userId, UserText.clean(bio), UserText.clean(homeTown), isPrivate);
		if (before.isPrivate() && !isPrivate) {
			this.repository.approveAllRequests(userId);
		}
		return me(userId);
	}

	/** Follows a public profile, or asks to follow a private one. Doing it again changes nothing. */
	@Transactional
	public Profile follow(String publicId, long viewerId) {
		ProfileRow target = visible(publicId, viewerId);
		if (target.userId() == viewerId) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_follow_self", "You can't follow yourself.");
		}
		if (this.repository.blocked(viewerId, target.userId())) {
			throw new ApiException(HttpStatus.CONFLICT, "blocked", "Unblock this person to follow them.");
		}
		this.repository.follow(viewerId, target.userId(),
				target.isPrivate() ? WanderbookRepository.PENDING : WanderbookRepository.ACCEPTED);
		return view(target, viewerId);
	}

	/** Unfollows, or cancels a request. */
	@Transactional
	public Profile unfollow(String publicId, long viewerId) {
		ProfileRow target = visible(publicId, viewerId);
		this.repository.unfollow(viewerId, target.userId());
		return view(target, viewerId);
	}

	/** A profile's posts, if the viewer may see them. */
	public Page<PostService.Post> posts(String publicId, Long viewerId, Long before) {
		ProfileRow owner = visible(publicId, viewerId);
		if (!view(owner, viewerId).canSeePosts()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "private_profile", "This profile is private.");
		}
		return this.posts.postsBy(owner.userId(), viewerId, before);
	}

	/** The account the public id belongs to, unless it blocked the viewer (for reports). */
	long userIdOf(String publicId, Long viewerId) {
		return visible(publicId, viewerId).userId();
	}

	public Page<Person> followRequests(long userId, Long before) {
		return page(this.repository.follows(userId, true, WanderbookRepository.PENDING, userId, before, PAGE_SIZE));
	}

	@Transactional
	public void approve(long userId, String requesterPublicId) {
		ProfileRow requester = existing(requesterPublicId);
		if (!this.repository.approve(requester.userId(), userId)) {
			throw notFound();
		}
	}

	/** Declines a request, or removes an existing follower. */
	@Transactional
	public void removeFollower(long userId, String followerPublicId) {
		ProfileRow follower = existing(followerPublicId);
		if (!this.repository.unfollow(follower.userId(), userId)) {
			throw notFound();
		}
	}

	/** Followers or following of a profile, newest first; hidden for a private profile the viewer doesn't follow. */
	public Page<Person> follows(String publicId, boolean followers, Long viewerId, Long before) {
		ProfileRow owner = visible(publicId, viewerId);
		if (!view(owner, viewerId).canSeePosts()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "private_profile", "This profile is private.");
		}
		return page(this.repository.follows(owner.userId(), followers, WanderbookRepository.ACCEPTED, viewerId, before,
				PAGE_SIZE));
	}

	@Transactional
	public Profile block(String publicId, long viewerId) {
		ProfileRow target = visible(publicId, viewerId);
		if (target.userId() == viewerId) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_block_self", "You can't block yourself.");
		}
		this.repository.block(viewerId, target.userId());
		return view(target, viewerId);
	}

	@Transactional
	public void unblock(String publicId, long viewerId) {
		this.repository.unblock(viewerId, existing(publicId).userId());
	}

	public List<Person> blocked(long userId) {
		return this.repository.blockedBy(userId).stream().map(Person::of).toList();
	}

	public List<Person> search(String text, Long viewerId) {
		return this.repository.search(text.trim(), viewerId, SEARCH_RESULTS).stream().map(Person::of).toList();
	}

	// ---- helpers ----

	private Profile view(ProfileRow profile, Long viewerId) {
		long id = profile.userId();
		boolean self = viewerId != null && viewerId == id;
		String status = (viewerId != null && !self) ? this.repository.followStatus(viewerId, id) : null;
		boolean blockedByYou = viewerId != null && !self && this.repository.blocked(viewerId, id);
		boolean followsYou = viewerId != null && !self
				&& WanderbookRepository.ACCEPTED.equals(this.repository.followStatus(id, viewerId));
		Relationship relationship = self ? Relationship.SELF
				: WanderbookRepository.ACCEPTED.equals(status) ? Relationship.FOLLOWING
						: WanderbookRepository.PENDING.equals(status) ? Relationship.REQUESTED : Relationship.NONE;
		boolean canSeePosts = !blockedByYou
				&& (self || !profile.isPrivate() || relationship == Relationship.FOLLOWING);
		return new Profile(profile.publicId(), profile.name(), profile.photoUrl(), this.posts.coverUrl(id),
				profile.bio(), profile.homeTown(), profile.isPrivate(), this.posts.postCount(id),
				this.repository.followerCount(id), this.repository.followingCount(id), relationship, followsYou,
				blockedByYou, canSeePosts, self ? this.repository.requestCount(id) : null);
	}

	/** The profile, unless its owner blocked the viewer (then it doesn't exist for them). */
	private ProfileRow visible(String publicId, Long viewerId) {
		ProfileRow profile = existing(publicId);
		if (viewerId != null && profile.userId() != viewerId && this.repository.blocked(profile.userId(), viewerId)) {
			throw notFound();
		}
		return profile;
	}

	private ProfileRow existing(String publicId) {
		return this.repository.byPublicId(publicId).orElseThrow(WanderbookService::notFound);
	}

	private ProfileRow profileOf(long userId) {
		return this.repository.byUserId(userId).orElseThrow(WanderbookService::notFound);
	}

	private static Page<Person> page(List<ListedProfile> rows) {
		List<Person> people = rows.stream().map(row -> Person.of(row.profile())).toList();
		Long next = (rows.size() == PAGE_SIZE) ? rows.get(rows.size() - 1).cursor() : null;
		return new Page<>(people, next);
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "profile_not_found", "We couldn't find that profile.");
	}

}
