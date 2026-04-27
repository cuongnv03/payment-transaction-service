package dev.cuong.payment.presentation.admin;

import dev.cuong.payment.application.dto.CircuitBreakerStatusResult;
import dev.cuong.payment.application.dto.DlqEventResult;
import dev.cuong.payment.application.dto.PagedResult;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.application.port.in.AdminUseCase;
import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.presentation.transaction.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin-only endpoints for DLQ management, cross-user transaction inspection,
 * and circuit-breaker observability.
 *
 * <p>All methods require the caller to hold the {@code ADMIN} role. Role enforcement
 * is applied at the class level via {@code @PreAuthorize} so it cannot be missed on
 * individual methods.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminUseCase adminUseCase;

    /**
     * Returns a paginated list of dead-letter events, newest first.
     *
     * @return 200 with paginated DLQ events; 401 if unauthenticated; 403 if not ADMIN
     */
    @GetMapping("/dlq")
    public ResponseEntity<PagedResult<DlqEventResponse>> getDlqEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PagedResult<DlqEventResult> result = adminUseCase.getDlqEvents(page, size);
        PagedResult<DlqEventResponse> response = new PagedResult<>(
                result.data().stream().map(this::toDlqResponse).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
        return ResponseEntity.ok(response);
    }

    /**
     * Re-publishes a dead-letter event to the main Kafka topic and marks it resolved.
     * Idempotent: calling with an already-resolved event ID is a no-op (still returns 204).
     *
     * @param id the DLQ event identifier
     * @return 204 on success; 404 if no event with that ID exists;
     *         401 if unauthenticated; 403 if not ADMIN
     */
    @PostMapping("/dlq/{id}/retry")
    public ResponseEntity<Void> retryDlqEvent(@PathVariable UUID id) {
        adminUseCase.retryDlqEvent(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns all transactions across all users, optionally filtered by status.
     * Paginated; newest first.
     *
     * @return 200 with paginated transactions; 401 if unauthenticated; 403 if not ADMIN
     */
    @GetMapping("/transactions")
    public ResponseEntity<PagedResult<TransactionResponse>> getAllTransactions(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PagedResult<TransactionResult> result = adminUseCase.getAllTransactions(status, page, size);
        PagedResult<TransactionResponse> response = new PagedResult<>(
                result.data().stream().map(this::toTransactionResponse).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the current state and call metrics of the {@code payment-gateway} circuit breaker.
     * Metric values are -1 when the sliding window does not yet have enough data.
     *
     * @return 200 with circuit breaker status; 401 if unauthenticated; 403 if not ADMIN
     */
    @GetMapping("/circuit-breaker")
    public ResponseEntity<CircuitBreakerStatusResponse> getCircuitBreakerStatus() {
        CircuitBreakerStatusResult result = adminUseCase.getCircuitBreakerStatus();
        return ResponseEntity.ok(new CircuitBreakerStatusResponse(
                result.name(),
                result.state(),
                result.failureRate(),
                result.slowCallRate(),
                result.bufferedCalls(),
                result.failedCalls()));
    }

    // ── Private mappers ───────────────────────────────────────────────────────

    private DlqEventResponse toDlqResponse(DlqEventResult r) {
        return new DlqEventResponse(
                r.id().toString(),
                r.topic(),
                r.kafkaPartition(),
                r.kafkaOffset(),
                r.payload(),
                r.transactionId() != null ? r.transactionId().toString() : null,
                r.eventType()     != null ? r.eventType().name()        : null,
                r.errorMessage(),
                r.retryCount(),
                r.createdAt(),
                r.resolvedAt(),
                r.resolvedBy());
    }

    private TransactionResponse toTransactionResponse(TransactionResult r) {
        return new TransactionResponse(
                r.id().toString(),
                r.fromAccountId().toString(),
                r.toAccountId().toString(),
                r.amount(),
                r.currency(),
                r.status(),
                r.description(),
                r.gatewayReference(),
                r.failureReason(),
                r.retryCount(),
                r.processedAt() != null ? r.processedAt().toString() : null,
                r.refundedAt()  != null ? r.refundedAt().toString()  : null,
                r.createdAt().toString(),
                r.updatedAt().toString());
    }
}
