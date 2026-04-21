package dev.cuong.payment.presentation.account;

import java.math.BigDecimal;

public record AccountResponse(
        String id,
        String userId,
        BigDecimal balance,
        String currency,
        String createdAt
) {}
