package dev.cuong.payment.infrastructure.persistence.repository;

import dev.cuong.payment.infrastructure.persistence.entity.TransactionJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, UUID> {

    Optional<TransactionJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    Page<TransactionJpaEntity> findByUserId(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);

    Optional<TransactionJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
