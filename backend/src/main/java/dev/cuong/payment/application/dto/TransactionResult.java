package dev.cuong.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResult(
        UUID id,
        UUID userId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String type,
        String status,
        String description,
        String idempotencyKey,
        String gatewayReference,
        String failureReason,
        Instant processedAt,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt
) {}
