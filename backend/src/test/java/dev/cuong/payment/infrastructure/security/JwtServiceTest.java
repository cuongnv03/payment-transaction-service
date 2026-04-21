package dev.cuong.payment.infrastructure.security;

import dev.cuong.payment.domain.vo.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    // 33 chars × 8 bits = 264 bits — meets HS256 minimum of 256 bits
    private static final String TEST_SECRET = "test-secret-key-minimum-32-chars!";
    private static final long EXPIRATION_MS = 3_600_000L; // 1 hour

    private JwtService jwtService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, EXPIRATION_MS);
        userId = UUID.randomUUID();
    }

    @Test
    void should_generate_parseable_token() {
        String token = jwtService.generateToken(userId, "alice", UserRole.USER);

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
    }

    @Test
    void should_embed_user_id_as_subject() {
        String token = jwtService.generateToken(userId, "alice", UserRole.USER);

        Claims claims = jwtService.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void should_embed_username_and_role_claims() {
        String token = jwtService.generateToken(userId, "alice", UserRole.ADMIN);

        Claims claims = jwtService.validateToken(token);

        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void should_reject_tampered_token() {
        String token = jwtService.generateToken(userId, "alice", UserRole.USER);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThatThrownBy(() -> jwtService.validateToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void should_reject_token_signed_with_different_secret() {
        JwtService otherService = new JwtService("another-secret-key-minimum-32-chars!", EXPIRATION_MS);
        String foreignToken = otherService.generateToken(userId, "alice", UserRole.USER);

        assertThatThrownBy(() -> jwtService.validateToken(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void should_reject_expired_token() {
        JwtService shortLivedService = new JwtService(TEST_SECRET, -1L); // already expired
        String expiredToken = shortLivedService.generateToken(userId, "alice", UserRole.USER);

        assertThatThrownBy(() -> jwtService.validateToken(expiredToken))
                .isInstanceOf(JwtException.class);
    }
}
