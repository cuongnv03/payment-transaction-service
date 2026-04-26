package dev.cuong.payment.infrastructure.persistence.adapter;

import dev.cuong.payment.application.port.out.AuditRepository;
import dev.cuong.payment.infrastructure.persistence.entity.AuditLogJpaEntity;
import dev.cuong.payment.infrastructure.persistence.repository.AuditLogJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditRepositoryAdapter implements AuditRepository {

    private final AuditLogJpaRepository jpaRepository;

    @Override
    public void record(AuditEntry entry) {
        AuditLogJpaEntity entity = AuditLogJpaEntity.builder()
                .transactionId(entry.transactionId())
                .userId(entry.userId())
                .eventType(entry.eventType())
                .newStatus(entry.newStatus())
                .metadata(entry.metadata())
                .build();
        jpaRepository.save(entity);
    }
}
