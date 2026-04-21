package dev.cuong.payment.infrastructure.persistence.mapper;

import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.infrastructure.persistence.entity.AccountJpaEntity;

public final class AccountMapper {

    private AccountMapper() {}

    public static Account toDomain(AccountJpaEntity entity) {
        return Account.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .balance(entity.getBalance())
                .currency(entity.getCurrency())
                .version(entity.getVersion() != null ? entity.getVersion() : 0L)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static AccountJpaEntity toEntity(Account account) {
        return AccountJpaEntity.builder()
                .id(account.getId())
                .userId(account.getUserId())
                .balance(account.getBalance())
                .currency(account.getCurrency())
                .version(account.getVersion())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }
}
