package dev.cuong.payment.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransactionCommand(
        UUID userId,
        UUID toAccountId,
        BigDecimal amount,
        String description,
        String idempotencyKey
) {}
