package dev.cuong.payment.application.port.out;

import dev.cuong.payment.domain.event.TransactionEventType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port for persisting and querying dead-letter events.
 *
 * <p>Dead-letter events are Kafka messages that failed consumer processing after the
 * maximum number of retries. They are stored here so administrators can inspect the
 * payload, investigate the root cause, and trigger re-delivery to the main topic.
 *
 * <p>Records in this store are append-only — the only mutation allowed is setting
 * {@code resolvedAt} when an admin retries or discards an event.
 */
public interface DeadLetterRepository {

    /**
     * Persists a new dead-letter entry produced by the Kafka error handler.
     *
     * @param entry the raw failure metadata — never update after insert
     */
    void save(DeadLetterEntry entry);

    /**
     * Returns paginated dead-letter events, newest first.
     *
     * @param page zero-based page index
     * @param size maximum number of items per page
     */
    List<DlqEvent> findAll(int page, int size);

    /** Total count of all dead-letter events (resolved + pending). */
    long count();

    /**
     * Returns a single DLQ event with pre-parsed {@code transactionId} and {@code eventType}.
     * The adapter performs the JSON parsing so the application service remains free of
     * infrastructure knowledge.
     *
     * @param id the DLQ event identifier
     * @return the event, or empty if not found
     */
    Optional<DlqEvent> findById(UUID id);

    /**
     * Marks the event as resolved — called by the admin retry or discard flow.
     *
     * @param id         the DLQ event to resolve
     * @param resolvedBy description of who/what resolved it (e.g. "admin-retry:{userId}")
     */
    void markResolved(UUID id, String resolvedBy);

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * Payload supplied by the Kafka error handler to persist a failed message.
     *
     * @param topic          the Kafka topic the message was consumed from
     * @param kafkaPartition the partition (nullable when failure precedes partition assignment)
     * @param kafkaOffset    the offset within the partition (nullable for the same reason)
     * @param payload        JSON-serialized message value as a string
     * @param errorMessage   the exception message from the final processing attempt
     */
    record DeadLetterEntry(
            String topic,
            Integer kafkaPartition,
            Long kafkaOffset,
            String payload,
            String errorMessage
    ) {}

    /**
     * Read model returned by find operations.
     *
     * <p>The adapter pre-parses {@code transactionId} and {@code eventType} from the raw
     * JSON payload so the service layer never needs to deal with JSON directly.
     *
     * @param transactionId parsed from the payload; {@code null} if payload was corrupt
     * @param eventType     parsed from the payload; {@code null} if payload was corrupt
     */
    record DlqEvent(
            UUID id,
            String topic,
            Integer kafkaPartition,
            Long kafkaOffset,
            String payload,
            UUID transactionId,
            TransactionEventType eventType,
            String errorMessage,
            int retryCount,
            Instant createdAt,
            Instant resolvedAt,
            String resolvedBy
    ) {}
}
