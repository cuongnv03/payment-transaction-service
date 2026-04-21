package dev.cuong.payment.presentation.user;

public record UserProfileResponse(
        String id,
        String username,
        String email,
        String role,
        String createdAt
) {}
