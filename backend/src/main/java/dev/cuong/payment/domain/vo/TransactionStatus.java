package dev.cuong.payment.domain.vo;

import dev.cuong.payment.domain.exception.InvalidTransactionStateException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum TransactionStatus {

    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    REFUNDED;

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = new EnumMap<>(TransactionStatus.class);
        ALLOWED_TRANSITIONS.put(PENDING,    EnumSet.of(PROCESSING));
        ALLOWED_TRANSITIONS.put(PROCESSING, EnumSet.of(SUCCESS, FAILED, TIMEOUT));
        ALLOWED_TRANSITIONS.put(SUCCESS,    EnumSet.of(REFUNDED));
        ALLOWED_TRANSITIONS.put(FAILED,     EnumSet.noneOf(TransactionStatus.class));
        ALLOWED_TRANSITIONS.put(TIMEOUT,    EnumSet.noneOf(TransactionStatus.class));
        ALLOWED_TRANSITIONS.put(REFUNDED,   EnumSet.noneOf(TransactionStatus.class));
    }

    /**
     * Validates and returns the target status.
     * Throws {@link InvalidTransactionStateException} if the transition is not allowed
     * by the state machine, making illegal state changes impossible to bypass.
     */
    public TransactionStatus transitionTo(TransactionStatus target) {
        if (!ALLOWED_TRANSITIONS.get(this).contains(target)) {
            throw new InvalidTransactionStateException(this, target);
        }
        return target;
    }

    public boolean isTerminal() {
        return this == FAILED || this == TIMEOUT || this == REFUNDED;
    }

    public boolean canBeRefunded() {
        return this == SUCCESS;
    }
}
