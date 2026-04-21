package dev.cuong.payment.presentation.auth;

public record AuthResponse(String token, String userId, String username, String role) {}
