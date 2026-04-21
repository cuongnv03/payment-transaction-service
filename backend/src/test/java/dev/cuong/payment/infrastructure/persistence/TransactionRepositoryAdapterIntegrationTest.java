package dev.cuong.payment.infrastructure.persistence;

import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.application.port.out.UserRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.model.User;
import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.domain.vo.TransactionType;
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

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setupUserAndAccount() {
        User user = userRepository.save(User.builder()
                .username("tx-test-user-" + UUID.randomUUID())
                .email("tx-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .role(UserRole.USER)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        userId = user.getId();

        Account account = accountRepository.save(Account.builder()
                .userId(userId)
                .balance(new BigDecimal("5000.00"))
                .currency("USD")
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        accountId = account.getId();
    }

    @Test
    void should_persist_and_find_transaction_by_id() {
        Transaction saved = transactionRepository.save(buildTransaction(null));

        Optional<Transaction> found = transactionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo("250.00");
        assertThat(found.get().getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(found.get().getType()).isEqualTo(TransactionType.PAYMENT);
    }

    @Test
    void should_find_transaction_scoped_to_user() {
        Transaction saved = transactionRepository.save(buildTransaction(null));

        Optional<Transaction> found = transactionRepository.findByIdAndUserId(saved.getId(), userId);
        Optional<Transaction> notFound = transactionRepository.findByIdAndUserId(saved.getId(), UUID.randomUUID());

        assertThat(found).isPresent();
        assertThat(notFound).isEmpty();
    }

    @Test
    void should_return_user_transactions_newest_first() {
        transactionRepository.save(buildTransaction(null));
        transactionRepository.save(buildTransaction(null));
        transactionRepository.save(buildTransaction(null));

        List<Transaction> page = transactionRepository.findByUserId(userId, 0, 10);

        assertThat(page).hasSize(3);
        // Verify descending order by checking each is not older than the next
        for (int i = 0; i < page.size() - 1; i++) {
            assertThat(page.get(i).getCreatedAt())
                    .isAfterOrEqualTo(page.get(i + 1).getCreatedAt());
        }
    }

    @Test
    void should_count_user_transactions() {
        transactionRepository.save(buildTransaction(null));
        transactionRepository.save(buildTransaction(null));

        long count = transactionRepository.countByUserId(userId);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_find_transaction_by_idempotency_key() {
        String idempotencyKey = "idem-key-" + UUID.randomUUID();
        transactionRepository.save(buildTransaction(idempotencyKey));

        Optional<Transaction> found = transactionRepository.findByIdempotencyKey(idempotencyKey);
        Optional<Transaction> missing = transactionRepository.findByIdempotencyKey("nonexistent-key");

        assertThat(found).isPresent();
        assertThat(missing).isEmpty();
    }

    @Test
    void should_not_return_other_users_transactions_in_count() {
        transactionRepository.save(buildTransaction(null));

        long countForOtherUser = transactionRepository.countByUserId(UUID.randomUUID());

        assertThat(countForOtherUser).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Transaction buildTransaction(String idempotencyKey) {
        Instant now = Instant.now();
        return Transaction.builder()
                .userId(userId)
                .accountId(accountId)
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .type(TransactionType.PAYMENT)
                .status(TransactionStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
