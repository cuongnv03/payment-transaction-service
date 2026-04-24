package dev.cuong.payment.domain.event;

/**
 * All lifecycle events a transaction can emit to the event bus.
 *
 * <p>Mirrors the transaction state machine: each terminal state change publishes one event.
 * Consumers (notification, audit, processing) use this discriminator to decide how to react.
 */
public enum TransactionEventType {
    CREATED,
    PROCESSING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    REFUNDED
}
