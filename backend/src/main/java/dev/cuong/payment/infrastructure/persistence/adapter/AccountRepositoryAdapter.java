package dev.cuong.payment.infrastructure.persistence.adapter;

import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.infrastructure.persistence.mapper.AccountMapper;
import dev.cuong.payment.infrastructure.persistence.repository.AccountJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccountRepositoryAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;

    @Override
    public Optional<Account> findById(UUID accountId) {
        return jpaRepository.findById(accountId).map(AccountMapper::toDomain);
    }

    @Override
    public Optional<Account> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).map(AccountMapper::toDomain);
    }

    @Override
    public Optional<Account> findByUserIdForUpdate(UUID userId) {
        return jpaRepository.findByUserIdWithLock(userId).map(AccountMapper::toDomain);
    }

    @Override
    public Account save(Account account) {
        return AccountMapper.toDomain(jpaRepository.save(AccountMapper.toEntity(account)));
    }
}
