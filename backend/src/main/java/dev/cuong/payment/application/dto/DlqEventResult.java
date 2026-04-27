package dev.cuong.payment.application.dto;

import dev.cuong.payment.domain.event.TransactionEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model representing a single dead-letter event — passed from service to presentation layer.
 *
 * @param transactionId pre-parsed from payload JSON; {@code null} if payload was unreadable
 * @param eventType     pre-parsed from payload JSON; {@code null} if payload was unreadable
 */
public record DlqEventResult(
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
