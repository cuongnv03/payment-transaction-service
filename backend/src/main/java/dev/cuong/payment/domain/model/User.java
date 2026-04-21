package dev.cuong.payment.domain.model;

import dev.cuong.payment.domain.vo.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class User {

    private final UUID id;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final UserRole role;
    private final Instant createdAt;
    private final Instant updatedAt;

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
