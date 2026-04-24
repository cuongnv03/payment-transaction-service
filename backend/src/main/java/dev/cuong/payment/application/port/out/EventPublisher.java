package dev.cuong.payment.application.port.out;

import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.domain.model.Transaction;

/**
 * Output port: publish transaction lifecycle events to the event bus.
 *
 * <p>Callers must invoke this <em>after</em> the transaction is persisted — the consumer
 * will read the row from the DB on receipt. Publishing before commit creates a narrow
 * window where the consumer cannot yet find the transaction (handled by retry in Task 15).
 */
public interface EventPublisher {

    /**
     * Publishes a transaction lifecycle event.
     *
     * @param transaction the transaction that triggered the event
     * @param eventType   the type of event that occurred
     */
    void publish(Transaction transaction, TransactionEventType eventType);
}
