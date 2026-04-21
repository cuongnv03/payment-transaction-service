package dev.cuong.payment.application.port.out;

import dev.cuong.payment.domain.model.User;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port: persistence operations for {@link User} aggregates.
 * Implemented by the infrastructure layer; never depends on JPA or Spring.
 */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    User save(User user);
}
