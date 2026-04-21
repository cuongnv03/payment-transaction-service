package dev.cuong.payment.presentation.transaction;

import java.math.BigDecimal;

public record TransactionResponse(
        String id,
        String userId,
        String accountId,
        BigDecimal amount,
        String currency,
        String type,
        String status,
        String description,
        String gatewayReference,
        String failureReason,
        String processedAt,
        String refundedAt,
        String createdAt,
        String updatedAt
) {}
