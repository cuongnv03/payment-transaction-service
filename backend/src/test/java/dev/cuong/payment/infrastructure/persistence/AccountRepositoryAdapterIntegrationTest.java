package dev.cuong.payment.infrastructure.persistence;

import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.UserRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.User;
import dev.cuong.payment.domain.vo.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepositoryAdapterIntegrationTest extends AbstractPersistenceIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setupUser() {
        User user = userRepository.save(User.builder()
                .username("acct-" + UUID.randomUUID())
                .email("acct-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role(UserRole.USER)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        userId = user.getId();
    }

    @Test
    void should_persist_and_find_account_by_user_id() {
        accountRepository.save(buildAccount(userId, "500.00"));

        Optional<Account> found = accountRepository.findByUserId(userId);

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(userId);
        assertThat(found.get().getBalance()).isEqualByComparingTo("500.00");
        assertThat(found.get().getCurrency()).isEqualTo("USD");
    }

    @Test
    void should_return_empty_when_account_not_found() {
        Optional<Account> found = accountRepository.findByUserId(UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    @Test
    void should_persist_balance_change_after_debit() {
        Account account = accountRepository.save(buildAccount(userId, "300.00"));
        account.debit(new BigDecimal("100.00"));
        accountRepository.save(account);

        Account reloaded = accountRepository.findByUserId(userId).orElseThrow();

        assertThat(reloaded.getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void should_persist_balance_change_after_credit() {
        Account account = accountRepository.save(buildAccount(userId, "100.00"));
        account.credit(new BigDecimal("50.00"));
        accountRepository.save(account);

        Account reloaded = accountRepository.findByUserId(userId).orElseThrow();

        assertThat(reloaded.getBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void should_find_account_for_pessimistic_lock_update() {
        accountRepository.save(buildAccount(userId, "1000.00"));

        Optional<Account> locked = accountRepository.findByUserIdForUpdate(userId);

        assertThat(locked).isPresent();
        assertThat(locked.get().getBalance()).isEqualByComparingTo("1000.00");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Account buildAccount(UUID ownerUserId, String balance) {
        Instant now = Instant.now();
        return Account.builder()
                .userId(ownerUserId)
                .balance(new BigDecimal(balance))
                .currency("USD")
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
