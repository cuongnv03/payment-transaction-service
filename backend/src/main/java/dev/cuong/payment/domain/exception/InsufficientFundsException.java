package dev.cuong.payment.domain.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends DomainException {

    private final UUID userId;
    private final BigDecimal balance;
    private final BigDecimal requested;

    public InsufficientFundsException(UUID userId, BigDecimal balance, BigDecimal requested) {
        super(String.format(
                "Insufficient funds for user %s: balance=%s, requested=%s",
                userId, balance, requested));
        this.userId = userId;
        this.balance = balance;
        this.requested = requested;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getRequested() {
        return requested;
    }
}
