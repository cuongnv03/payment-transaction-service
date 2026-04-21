package dev.cuong.payment.domain.model;

import dev.cuong.payment.domain.exception.InsufficientFundsException;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class Account {

    private final UUID id;
    private final UUID userId;
    private BigDecimal balance;
    private final String currency;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    /**
     * Reduces the balance by {@code amount}.
     * Throws {@link InsufficientFundsException} if the account has insufficient funds,
     * enforcing the non-negative balance invariant at the domain layer.
     */
    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(userId, balance, amount);
        }
        this.balance = balance.subtract(amount);
        this.updatedAt = Instant.now();
    }

    public void credit(BigDecimal amount) {
        this.balance = balance.add(amount);
        this.updatedAt = Instant.now();
    }
}
