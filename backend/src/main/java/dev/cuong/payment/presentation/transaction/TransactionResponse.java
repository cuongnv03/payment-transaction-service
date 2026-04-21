package dev.cuong.payment.presentation.transaction;

import java.math.BigDecimal;

public record TransactionResponse(
        String id,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String description,
        String gatewayReference,
        String failureReason,
        int retryCount,
        String processedAt,
        String refundedAt,
        String createdAt,
        String updatedAt
) {}
