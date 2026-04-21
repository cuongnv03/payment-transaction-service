package dev.cuong.payment.application.dto;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResult(
        UUID id,
        String username,
        String email,
        String role,
        Instant createdAt
) {}
