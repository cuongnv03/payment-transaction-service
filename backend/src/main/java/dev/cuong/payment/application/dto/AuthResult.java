package dev.cuong.payment.application.dto;

import java.util.UUID;

public record AuthResult(String token, UUID userId, String username, String role) {}
