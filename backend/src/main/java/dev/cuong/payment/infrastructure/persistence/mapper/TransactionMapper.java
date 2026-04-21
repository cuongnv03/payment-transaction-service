package dev.cuong.payment.infrastructure.persistence.mapper;

import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.infrastructure.persistence.entity.TransactionJpaEntity;

public final class TransactionMapper {

    private TransactionMapper() {}

    public static Transaction toDomain(TransactionJpaEntity entity) {
        return Transaction.builder()
                .id(entity.getId())
                .fromAccountId(entity.getFromAccountId())
                .toAccountId(entity.getToAccountId())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .status(entity.getStatus())
                .description(entity.getDescription())
                .idempotencyKey(entity.getIdempotencyKey())
                .gatewayReference(entity.getGatewayReference())
                .failureReason(entity.getFailureReason())
                .retryCount(entity.getRetryCount())
                .version(entity.getVersion() != null ? entity.getVersion() : 0L)
                .processedAt(entity.getProcessedAt())
                .refundedAt(entity.getRefundedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static TransactionJpaEntity toEntity(Transaction tx) {
        return TransactionJpaEntity.builder()
                .id(tx.getId())
                .fromAccountId(tx.getFromAccountId())
                .toAccountId(tx.getToAccountId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .status(tx.getStatus())
                .description(tx.getDescription())
                .idempotencyKey(tx.getIdempotencyKey())
                .gatewayReference(tx.getGatewayReference())
                .failureReason(tx.getFailureReason())
                .retryCount(tx.getRetryCount())
                .version(tx.getVersion())
                .processedAt(tx.getProcessedAt())
                .refundedAt(tx.getRefundedAt())
                .createdAt(tx.getCreatedAt())
                .updatedAt(tx.getUpdatedAt())
                .build();
    }
}
