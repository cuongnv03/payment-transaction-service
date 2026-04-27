package dev.cuong.payment.infrastructure.persistence.repository;

import dev.cuong.payment.infrastructure.persistence.entity.DeadLetterEventJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeadLetterEventJpaRepository extends JpaRepository<DeadLetterEventJpaEntity, UUID> {

    Page<DeadLetterEventJpaEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
