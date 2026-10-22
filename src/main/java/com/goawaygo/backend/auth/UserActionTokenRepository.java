package com.goawaygo.backend.auth;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface UserActionTokenRepository extends JpaRepository<UserActionToken, Long> {

	/** Locks the row so a token can't be redeemed twice by concurrent requests. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<UserActionToken> findByTokenHash(String tokenHash);

	/** Retires a user's outstanding tokens of one purpose, so only the newest emailed link works. */
	@Modifying
	@Query("update UserActionToken t set t.usedAt = :now where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
	int retireUnused(long userId, UserActionToken.Purpose purpose, Instant now);

}
