package dev.cuong.payment.infrastructure.kafka;

import dev.cuong.payment.domain.event.TransactionEventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka message payload for transaction lifecycle events on {@code payment.transaction.events}.
 *
 * <p>All consumers (processing, notification, audit) deserialize this record.
 * Field names and types are a published contract — any change is backward-incompatible.
 */
public record TransactionEventMessage(
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        String status,
        TransactionEventType eventType,
        String description,
        String gatewayReference,
        String failureReason,
        int retryCount,
        Instant occurredAt
) {}
