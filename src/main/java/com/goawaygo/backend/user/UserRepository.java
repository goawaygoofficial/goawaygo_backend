package com.goawaygo.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	/** @param email an already-normalized email (see {@link User#normalizeEmail}) */
	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	Optional<User> findByGoogleSubject(String googleSubject);

}
