package dev.cuong.payment.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code dead_letter_events} table.
 *
 * <p>Rows are inserted by the Kafka DLQ recoverer and updated (resolved_at, resolved_by)
 * only when an admin retries or discards the event. No other mutations are allowed.
 */
@Entity
@Table(name = "dead_letter_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DeadLetterEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @Column(name = "kafka_partition")
    private Integer kafkaPartition;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    // Raw JSON string of the original TransactionEventMessage — TEXT in Postgres, no length limit
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Called when an admin resolves this event via retry or discard. */
    public void resolve(String resolvedBy) {
        this.resolvedAt = Instant.now();
        this.resolvedBy = resolvedBy;
    }
}
