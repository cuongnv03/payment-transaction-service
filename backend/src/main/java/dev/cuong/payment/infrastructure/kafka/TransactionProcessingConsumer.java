package dev.cuong.payment.infrastructure.kafka;

import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.DistributedLockPort;
import dev.cuong.payment.application.port.out.EventPublisher;
import dev.cuong.payment.application.port.out.PaymentGatewayPort;
import dev.cuong.payment.application.port.out.TransactionMetricsPort;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.domain.exception.PaymentGatewayException;
import dev.cuong.payment.domain.exception.PaymentGatewayTimeoutException;
import dev.cuong.payment.domain.exception.TransactionNotFoundException;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka consumer that drives the payment transaction state machine from PENDING to a terminal state.
 *
 * <p><strong>Processing flow per CREATED event:</strong>
 * <ol>
 *   <li>Acquire a Redis distributed lock on {@code transactionId} (at-most-once guard across instances).
 *   <li>In a short DB transaction: verify PENDING → save as PROCESSING (optimistic lock version check).
 *   <li>Outside any DB transaction: call the payment gateway (circuit breaker + retry).
 *   <li>In a second short DB transaction: record SUCCESS/FAILED/TIMEOUT, update account balances.
 *   <li>Release the lock.
 * </ol>
 *
 * <p>Two separate DB transactions are used intentionally: holding a connection open during a
 * network call to the gateway would exhaust the connection pool under load. The gap between
 * the two transactions is safe because the distributed lock prevents concurrent processing,
 * and the optimistic lock prevents the edge case where the Redis lock expires mid-flight.
 *
 * <p>Non-CREATED events (PROCESSING, SUCCESS, FAILED, TIMEOUT, REFUNDED) are forwarded by
 * the notification consumer (Task 13) and audit consumer (Task 14); this consumer ignores them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionProcessingConsumer {

    private static final String LOCK_PREFIX = "lock:processing:tx:";
    private static final long LOCK_LEASE_SECONDS = 60;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final PaymentGatewayPort paymentGateway;
    private final DistributedLockPort distributedLock;
    private final EventPublisher eventPublisher;
    private final TransactionMetricsPort metrics;
    private final PlatformTransactionManager transactionManager;

    // TransactionTemplate is derived from transactionManager — not a Spring bean itself.
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void init() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transaction-events}",
            groupId = "payment-processor"
    )
    public void onTransactionEvent(TransactionEventMessage message) {
        if (message.eventType() != TransactionEventType.CREATED) {
            return;
        }

        UUID transactionId = message.transactionId();
        String lockKey = LOCK_PREFIX + transactionId;

        boolean locked = distributedLock.tryLock(lockKey, 0, LOCK_LEASE_SECONDS);
        if (!locked) {
            log.warn("Could not acquire processing lock — another instance is handling: transactionId={}", transactionId);
            return;
        }

        try {
            process(transactionId);
        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict — transaction already processed by another instance: transactionId={}", transactionId);
        } catch (Exception e) {
            log.error("Unexpected error processing transaction: transactionId={}, error={}", transactionId, e.getMessage(), e);
            throw e; // propagate so Kafka retries via DefaultErrorHandler
        } finally {
            distributedLock.unlock(lockKey);
        }
    }

    private void process(UUID transactionId) {
        // ── Phase 1: PENDING → PROCESSING ────────────────────────────────────
        Transaction tx = transactionTemplate.execute(status -> {
            Transaction loaded = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));

            if (loaded.getStatus() != TransactionStatus.PENDING) {
                log.info("Skipping already-processed transaction: transactionId={}, currentStatus={}",
                        transactionId, loaded.getStatus());
                return null;
            }

            loaded.startProcessing();
            Transaction saved = transactionRepository.save(loaded);
            eventPublisher.publish(saved, TransactionEventType.PROCESSING);
            log.info("Transaction PROCESSING: transactionId={}", transactionId);
            return saved;
        });

        if (tx == null) {
            return; // already past PENDING — idempotent skip
        }

        // ── Phase 2: Gateway call + finalisation ──────────────────────────────
        // Runs OUTSIDE any DB transaction — the gateway call is an IO operation
        // and should not hold a DB connection open for its duration.
        // Timer starts after Phase 1 so we measure gateway+finalisation latency,
        // not DB-only "still PENDING" overhead.
        Instant start = Instant.now();
        TransactionStatus terminalStatus;
        try {
            String gatewayRef = paymentGateway.charge(tx.getId(), tx.getAmount());
            finaliseSuccess(tx.getId(), tx.getToAccountId(), tx.getAmount(), gatewayRef);
            terminalStatus = TransactionStatus.SUCCESS;

        } catch (PaymentGatewayTimeoutException e) {
            log.warn("Gateway timeout after retries — marking TIMEOUT: transactionId={}", transactionId);
            finaliseFailure(tx.getId(), tx.getFromAccountId(), tx.getAmount(), TransactionStatus.TIMEOUT,
                    "Gateway timeout after retries");
            terminalStatus = TransactionStatus.TIMEOUT;

        } catch (PaymentGatewayException e) {
            log.warn("Gateway rejected payment — marking FAILED: transactionId={}, reason={}", transactionId, e.getMessage());
            finaliseFailure(tx.getId(), tx.getFromAccountId(), tx.getAmount(), TransactionStatus.FAILED,
                    e.getMessage());
            terminalStatus = TransactionStatus.FAILED;

        } catch (CallNotPermittedException e) {
            log.error("Circuit breaker OPEN — marking FAILED: transactionId={}", transactionId);
            finaliseFailure(tx.getId(), tx.getFromAccountId(), tx.getAmount(), TransactionStatus.FAILED,
                    "Payment gateway unavailable (circuit breaker open)");
            terminalStatus = TransactionStatus.FAILED;
        }

        metrics.recordProcessed(terminalStatus.name(), Duration.between(start, Instant.now()));
    }

    private void finaliseSuccess(UUID transactionId, UUID toAccountId, BigDecimal amount, String gatewayRef) {
        transactionTemplate.executeWithoutResult(status -> {
            Transaction tx = transactionRepository.findById(transactionId).orElseThrow();
            tx.complete(gatewayRef);
            transactionRepository.save(tx);

            Account toAccount = accountRepository.findByIdForUpdate(toAccountId).orElseThrow();
            toAccount.credit(amount);
            accountRepository.save(toAccount);

            log.info("Transaction SUCCESS: transactionId={}, gatewayRef={}, creditedAccount={}",
                    transactionId, gatewayRef, toAccountId);
            eventPublisher.publish(tx, TransactionEventType.SUCCESS);
        });
    }

    private void finaliseFailure(UUID transactionId, UUID fromAccountId, BigDecimal amount,
                                  TransactionStatus finalStatus, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Transaction tx = transactionRepository.findById(transactionId).orElseThrow();

            if (finalStatus == TransactionStatus.TIMEOUT) {
                tx.timeout();
            } else {
                tx.fail(reason);
            }
            transactionRepository.save(tx);

            // Restore sender's balance — funds were held on CREATED
            Account fromAccount = accountRepository.findByIdForUpdate(fromAccountId).orElseThrow();
            fromAccount.credit(amount);
            accountRepository.save(fromAccount);

            log.info("Transaction {}: transactionId={}, reason={}, restoredAccount={}",
                    finalStatus, transactionId, reason, fromAccountId);

            TransactionEventType eventType = finalStatus == TransactionStatus.TIMEOUT
                    ? TransactionEventType.TIMEOUT
                    : TransactionEventType.FAILED;
            eventPublisher.publish(tx, eventType);
        });
    }
}
