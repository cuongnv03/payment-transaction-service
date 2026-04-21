package dev.cuong.payment.presentation.transaction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull(message = "toAccountId is required")
        UUID toAccountId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be positive")
        BigDecimal amount,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description
) {}
