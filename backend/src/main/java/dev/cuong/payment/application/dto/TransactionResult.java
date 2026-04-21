package dev.cuong.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResult(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String description,
        String idempotencyKey,
        String gatewayReference,
        String failureReason,
        int retryCount,
        Instant processedAt,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt
) {}
