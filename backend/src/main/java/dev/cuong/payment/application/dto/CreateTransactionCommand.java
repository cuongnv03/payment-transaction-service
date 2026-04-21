package dev.cuong.payment.application.dto;

import dev.cuong.payment.domain.vo.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransactionCommand(
        UUID userId,
        BigDecimal amount,
        TransactionType type,
        String description,
        String idempotencyKey
) {}
