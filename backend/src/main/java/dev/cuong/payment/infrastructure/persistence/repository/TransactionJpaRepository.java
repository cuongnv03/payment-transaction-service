package dev.cuong.payment.infrastructure.persistence.repository;

import dev.cuong.payment.infrastructure.persistence.entity.TransactionJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, UUID> {

    Optional<TransactionJpaEntity> findByIdAndFromAccountId(UUID id, UUID fromAccountId);

    Page<TransactionJpaEntity> findByFromAccountId(UUID fromAccountId, Pageable pageable);

    long countByFromAccountId(UUID fromAccountId);

    Optional<TransactionJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
