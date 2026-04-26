package dev.cuong.payment.application.port.out;

import java.util.UUID;

/**
 * Output port for persisting immutable audit log entries.
 *
 * <p>Audit records must never be updated or deleted — each call to {@link #record}
 * produces a new, append-only row. Implementations must be {@code @Transactional}
 * so that a DB failure rolls back the write and allows the Kafka consumer to retry.
 */
public interface AuditRepository {

    /**
     * Persists a single audit entry to the append-only audit log.
     *
     * @param entry the audit record to persist — immutable after creation
     */
    void record(AuditEntry entry);

    /**
     * Payload passed from the Kafka consumer layer to the persistence adapter.
     *
     * @param transactionId the transaction this event belongs to (nullable for
     *                      user-scoped events such as login)
     * @param userId        the user who owns the {@code fromAccount}
     * @param eventType     string representation of {@link dev.cuong.payment.domain.event.TransactionEventType}
     * @param newStatus     the transaction status after this event
     * @param metadata      JSON-encoded supplemental fields (amount, accounts, gateway ref, etc.)
     */
    record AuditEntry(
            UUID transactionId,
            UUID userId,
            String eventType,
            String newStatus,
            String metadata
    ) {}
}
