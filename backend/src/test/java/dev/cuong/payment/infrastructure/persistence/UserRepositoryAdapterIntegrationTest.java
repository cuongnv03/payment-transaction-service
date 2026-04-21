package dev.cuong.payment.infrastructure.persistence;

import dev.cuong.payment.application.port.out.UserRepository;
import dev.cuong.payment.domain.model.User;
import dev.cuong.payment.domain.vo.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserRepositoryAdapterIntegrationTest extends AbstractPersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void should_persist_and_find_user_by_id() {
        User saved = userRepository.save(buildUser("alice", "alice@example.com"));

        Optional<User> found = userRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("alice");
        assertThat(found.get().getEmail()).isEqualTo("alice@example.com");
        assertThat(found.get().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void should_find_user_by_username() {
        userRepository.save(buildUser("bob", "bob@example.com"));

        Optional<User> found = userRepository.findByUsername("bob");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void should_find_user_by_email() {
        userRepository.save(buildUser("carol", "carol@example.com"));

        Optional<User> found = userRepository.findByEmail("carol@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("carol");
    }

    @Test
    void should_return_empty_when_user_not_found_by_id() {
        Optional<User> found = userRepository.findById(UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    @Test
    void should_detect_duplicate_username() {
        userRepository.save(buildUser("dave", "dave@example.com"));

        assertThat(userRepository.existsByUsername("dave")).isTrue();
        assertThat(userRepository.existsByUsername("unknown")).isFalse();
    }

    @Test
    void should_detect_duplicate_email() {
        userRepository.save(buildUser("eve", "eve@example.com"));

        assertThat(userRepository.existsByEmail("eve@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User buildUser(String username, String email) {
        Instant now = Instant.now();
        return User.builder()
                .username(username)
                .email(email)
                .passwordHash("$2a$10$hashedpassword")
                .role(UserRole.USER)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
