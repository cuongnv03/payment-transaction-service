package dev.cuong.payment.infrastructure.persistence.repository;

import dev.cuong.payment.infrastructure.persistence.entity.AccountJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    Optional<AccountJpaEntity> findByUserId(UUID userId);

    // PESSIMISTIC_WRITE → SELECT ... FOR UPDATE. Must be called within
    // an active @Transactional boundary or PostgreSQL will reject the lock mode.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountJpaEntity a WHERE a.userId = :userId")
    Optional<AccountJpaEntity> findByUserIdWithLock(@Param("userId") UUID userId);
}
