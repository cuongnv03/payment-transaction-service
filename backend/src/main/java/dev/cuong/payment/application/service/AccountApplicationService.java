package dev.cuong.payment.application.service;

import dev.cuong.payment.application.dto.AccountResult;
import dev.cuong.payment.application.port.in.GetAccountUseCase;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.domain.exception.AccountNotFoundException;
import dev.cuong.payment.domain.model.Account;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountApplicationService implements GetAccountUseCase {

    private final AccountRepository accountRepository;

    @Override
    @Transactional(readOnly = true)
    public AccountResult getAccount(UUID userId) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        log.info("Account retrieved: userId={}, accountId={}", userId, account.getId());
        return new AccountResult(
                account.getId(),
                account.getUserId(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt());
    }
}
