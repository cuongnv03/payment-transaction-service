package dev.cuong.payment.application.service;

import dev.cuong.payment.application.dto.CircuitBreakerStatusResult;
import dev.cuong.payment.application.dto.DlqEventResult;
import dev.cuong.payment.application.dto.PagedResult;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.application.port.in.AdminUseCase;
import dev.cuong.payment.application.port.out.DeadLetterRepository;
import dev.cuong.payment.application.port.out.DeadLetterRepository.DlqEvent;
import dev.cuong.payment.application.port.out.EventPublisher;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.exception.DlqEventNotFoundException;
import dev.cuong.payment.domain.exception.TransactionNotFoundException;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService implements AdminUseCase {

    private static final String CIRCUIT_BREAKER_NAME = "payment-gateway";

    private final DeadLetterRepository deadLetterRepository;
    private final TransactionRepository transactionRepository;
    private final EventPublisher eventPublisher;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public PagedResult<DlqEventResult> getDlqEvents(int page, int size) {
        List<DlqEventResult> results = deadLetterRepository.findAll(page, size)
                .stream()
                .map(this::toDlqResult)
                .toList();
        long total = deadLetterRepository.count();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PagedResult<>(results, page, size, total, totalPages);
    }

    @Override
    @Transactional
    public void retryDlqEvent(UUID dlqEventId) {
        DlqEvent event = deadLetterRepository.findById(dlqEventId)
                .orElseThrow(() -> new DlqEventNotFoundException(dlqEventId));

        if (event.resolvedAt() != null) {
            log.warn("[ADMIN] DLQ event already resolved — ignoring retry: dlqEventId={}", dlqEventId);
            return;
        }

        if (event.transactionId() == null || event.eventType() == null) {
            log.warn("[ADMIN] Cannot retry DLQ event — transactionId or eventType missing " +
                    "(payload was unreadable): dlqEventId={}", dlqEventId);
            deadLetterRepository.markResolved(dlqEventId, "admin-discard:unreadable-payload");
            return;
        }

        Transaction tx = transactionRepository.findById(event.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(event.transactionId()));

        eventPublisher.publish(tx, event.eventType());
        deadLetterRepository.markResolved(dlqEventId, "admin-retry");

        log.info("[ADMIN] DLQ event retried: dlqEventId={}, transactionId={}, eventType={}",
                dlqEventId, event.transactionId(), event.eventType());
    }

    @Override
    public PagedResult<TransactionResult> getAllTransactions(TransactionStatus status, int page, int size) {
        List<Transaction> transactions;
        long total;

        if (status == null) {
            transactions = transactionRepository.findAllTransactions(page, size);
            total = transactionRepository.countAllTransactions();
        } else {
            transactions = transactionRepository.findAllTransactionsByStatus(status, page, size);
            total = transactionRepository.countAllTransactionsByStatus(status);
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        List<TransactionResult> results = transactions.stream().map(this::toTransactionResult).toList();
        return new PagedResult<>(results, page, size, total, totalPages);
    }

    @Override
    public CircuitBreakerStatusResult getCircuitBreakerStatus() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        CircuitBreaker.Metrics metrics = cb.getMetrics();

        // Resilience4j returns -1.0 when not enough calls have been recorded yet
        float failureRate  = Math.max(metrics.getFailureRate(),  -1f);
        float slowCallRate = Math.max(metrics.getSlowCallRate(), -1f);

        return new CircuitBreakerStatusResult(
                CIRCUIT_BREAKER_NAME,
                cb.getState().name(),
                failureRate,
                slowCallRate,
                metrics.getNumberOfBufferedCalls(),
                metrics.getNumberOfFailedCalls()
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private DlqEventResult toDlqResult(DlqEvent e) {
        return new DlqEventResult(
                e.id(), e.topic(), e.kafkaPartition(), e.kafkaOffset(),
                e.payload(), e.transactionId(), e.eventType(),
                e.errorMessage(), e.retryCount(),
                e.createdAt(), e.resolvedAt(), e.resolvedBy()
        );
    }

    private TransactionResult toTransactionResult(Transaction tx) {
        return new TransactionResult(
                tx.getId(),
                tx.getFromAccountId(),
                tx.getToAccountId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus().name(),
                tx.getDescription(),
                tx.getIdempotencyKey(),
                tx.getGatewayReference(),
                tx.getFailureReason(),
                tx.getRetryCount(),
                tx.getProcessedAt(),
                tx.getRefundedAt(),
                tx.getCreatedAt(),
                tx.getUpdatedAt()
        );
    }
}
