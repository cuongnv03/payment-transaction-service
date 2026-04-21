package dev.cuong.payment.presentation.transaction;

import dev.cuong.payment.domain.vo.TransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateTransactionRequest(
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be positive")
        BigDecimal amount,

        @NotNull(message = "type is required")
        TransactionType type,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description
) {}
