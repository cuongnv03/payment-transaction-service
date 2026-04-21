package dev.cuong.payment.infrastructure.persistence;

import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.application.port.out.UserRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.model.User;
import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.domain.vo.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRepositoryAdapterIntegrationTest extends AbstractPersistenceIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID fromAccountId;
    private UUID toAccountId;

    @BeforeEach
    void setupAccounts() {
        fromAccountId = createAccountForNewUser("sender");
        toAccountId   = createAccountForNewUser("receiver");
    }

    // ── Basic CRUD ────────────────────────────────────────────────────────────

    @Test
    void should_persist_and_find_transaction_by_id() {
        Transaction saved = transactionRepository.save(buildTransaction(null));

        Optional<Transaction> found = transactionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo("250.00");
        assertThat(found.get().getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(found.get().getFromAccountId()).isEqualTo(fromAccountId);
        assertThat(found.get().getToAccountId()).isEqualTo(toAccountId);
    }

    // ── Scoped access ─────────────────────────────────────────────────────────

    @Test
    void should_find_transaction_scoped_to_from_account() {
        Transaction saved = transactionRepository.save(buildTransaction(null));

        Optional<Transaction> found    = transactionRepository.findByIdAndFromAccountId(saved.getId(), fromAccountId);
        Optional<Transaction> notFound = transactionRepository.findByIdAndFromAccountId(saved.getId(), UUID.randomUUID());

        assertThat(found).isPresent();
        assertThat(notFound).isEmpty();
    }

    @Test
    void should_return_sender_transactions_newest_first() {
        transactionRepository.save(buildTransaction(null));
        transactionRepository.save(buildTransaction(null));
        transactionRepository.save(buildTransaction(null));

        List<Transaction> page = transactionRepository.findByFromAccountId(fromAccountId, 0, 10);

        assertThat(page).hasSize(3);
        for (int i = 0; i < page.size() - 1; i++) {
            assertThat(page.get(i).getCreatedAt())
                    .isAfterOrEqualTo(page.get(i + 1).getCreatedAt());
        }
    }

    @Test
    void should_count_sender_transactions() {
        transactionRepository.save(buildTransaction(null));
        transactionRepository.save(buildTransaction(null));

        assertThat(transactionRepository.countByFromAccountId(fromAccountId)).isEqualTo(2);
        assertThat(transactionRepository.countByFromAccountId(toAccountId)).isZero();
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    void should_find_transaction_by_idempotency_key() {
        String key = "idem-key-" + UUID.randomUUID();
        transactionRepository.save(buildTransaction(key));

        assertThat(transactionRepository.findByIdempotencyKey(key)).isPresent();
        assertThat(transactionRepository.findByIdempotencyKey("nonexistent-key")).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createAccountForNewUser(String label) {
        User user = userRepository.save(User.builder()
                .username(label + "-" + UUID.randomUUID())
                .email(label + "-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role(UserRole.USER)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        Account account = accountRepository.save(Account.builder()
                .userId(user.getId())
                .balance(new BigDecimal("5000.00"))
                .currency("USD")
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        return account.getId();
    }

    private Transaction buildTransaction(String idempotencyKey) {
        Instant now = Instant.now();
        return Transaction.builder()
                .fromAccountId(fromAccountId)
                .toAccountId(toAccountId)
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .status(TransactionStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .retryCount(0)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
