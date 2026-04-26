package dev.cuong.payment.infrastructure.persistence.repository;

import dev.cuong.payment.infrastructure.persistence.entity.AuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, UUID> {

    List<AuditLogJpaEntity> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    long countByTransactionId(UUID transactionId);
}
