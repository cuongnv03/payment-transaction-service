package dev.cuong.payment.presentation.admin;

import java.time.Instant;

public record DlqEventResponse(
        String id,
        String topic,
        Integer kafkaPartition,
        Long kafkaOffset,
        String payload,
        String transactionId,
        String eventType,
        String errorMessage,
        int retryCount,
        Instant createdAt,
        Instant resolvedAt,
        String resolvedBy
) {}
