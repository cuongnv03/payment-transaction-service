package dev.cuong.payment.infrastructure.persistence.mapper;

import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.infrastructure.persistence.entity.TransactionJpaEntity;

public final class TransactionMapper {

    private TransactionMapper() {}

    public static Transaction toDomain(TransactionJpaEntity entity) {
        return Transaction.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .accountId(entity.getAccountId())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .type(entity.getType())
                .status(entity.getStatus())
                .description(entity.getDescription())
                .idempotencyKey(entity.getIdempotencyKey())
                .gatewayReference(entity.getGatewayReference())
                .failureReason(entity.getFailureReason())
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
                .userId(tx.getUserId())
                .accountId(tx.getAccountId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .type(tx.getType())
                .status(tx.getStatus())
                .description(tx.getDescription())
                .idempotencyKey(tx.getIdempotencyKey())
                .gatewayReference(tx.getGatewayReference())
                .failureReason(tx.getFailureReason())
                .version(tx.getVersion())
                .processedAt(tx.getProcessedAt())
                .refundedAt(tx.getRefundedAt())
                .createdAt(tx.getCreatedAt())
                .updatedAt(tx.getUpdatedAt())
                .build();
    }
}
