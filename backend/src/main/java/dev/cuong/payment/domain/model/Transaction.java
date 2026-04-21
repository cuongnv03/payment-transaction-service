package dev.cuong.payment.domain.model;

import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.domain.vo.TransactionType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root for a payment transaction.
 *
 * <p>All status changes are routed through the business methods below, which
 * delegate to {@link TransactionStatus#transitionTo} to enforce the state machine.
 * It is impossible to set {@code status} directly — there is no setter.
 */
@Getter
@Builder
public class Transaction {

    private final UUID id;
    private final UUID userId;
    private final UUID accountId;
    private final BigDecimal amount;
    private final String currency;
    private final TransactionType type;
    private TransactionStatus status;
    private final String description;
    private final String idempotencyKey;
    private String gatewayReference;
    private String failureReason;
    private long version;
    private Instant processedAt;
    private Instant refundedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public void startProcessing() {
        this.status = status.transitionTo(TransactionStatus.PROCESSING);
        this.updatedAt = Instant.now();
    }

    public void complete(String gatewayRef) {
        this.status = status.transitionTo(TransactionStatus.SUCCESS);
        this.gatewayReference = gatewayRef;
        this.processedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = status.transitionTo(TransactionStatus.FAILED);
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public void timeout() {
        this.status = status.transitionTo(TransactionStatus.TIMEOUT);
        this.updatedAt = Instant.now();
    }

    public void refund() {
        this.status = status.transitionTo(TransactionStatus.REFUNDED);
        this.refundedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
