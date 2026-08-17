package com.semanticdocs.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data writes the implementation at runtime. "findByEmail" is parsed into
 * "SELECT u FROM User u WHERE u.email = ?1" purely from the method name.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
