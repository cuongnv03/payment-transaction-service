package dev.cuong.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResult(
        UUID id,
        UUID userId,
        BigDecimal balance,
        String currency,
        Instant createdAt
) {}
