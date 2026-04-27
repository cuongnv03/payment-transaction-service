package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.CircuitBreakerStatusResult;
import dev.cuong.payment.application.dto.DlqEventResult;
import dev.cuong.payment.application.dto.PagedResult;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.domain.vo.TransactionStatus;

import java.util.UUID;

/**
 * Admin use cases — all methods must be called by a user with the ADMIN role.
 *
 * <p>Role enforcement is performed at the {@link dev.cuong.payment.presentation.admin.AdminController}
 * layer via {@code @PreAuthorize("hasRole('ADMIN')")}. The service itself does not re-check
 * the role — it trusts that the presentation layer has already verified authorization.
 */
public interface AdminUseCase {

    /**
     * Returns a paginated list of dead-letter events, newest first.
     *
     * @param page zero-based page index
     * @param size maximum number of items per page
     */
    PagedResult<DlqEventResult> getDlqEvents(int page, int size);

    /**
     * Re-publishes a dead-letter event to the main Kafka topic and marks it resolved.
     *
     * <p>The original event type is preserved: if a PROCESSING event failed in the audit
     * consumer, the retry re-publishes a PROCESSING event — giving every consumer
     * (notification, audit) another chance. The processing consumer ignores non-CREATED events,
     * so re-publishing non-CREATED events does not cause double payment processing.
     *
     * <p>If the DLQ event has already been resolved, the call is a no-op (idempotent).
     *
     * @param dlqEventId the ID of the DLQ record to retry
     * @throws dev.cuong.payment.domain.exception.DlqEventNotFoundException if no event with that ID exists
     */
    void retryDlqEvent(UUID dlqEventId);

    /**
     * Returns all transactions across all users, optionally filtered by status.
     *
     * @param status optional status filter; {@code null} returns all statuses
     * @param page   zero-based page index
     * @param size   maximum number of items per page
     */
    PagedResult<TransactionResult> getAllTransactions(TransactionStatus status, int page, int size);

    /**
     * Returns the current state and call metrics of the {@code payment-gateway} circuit breaker.
     * Metrics values are -1 when the sliding window does not yet have enough data.
     */
    CircuitBreakerStatusResult getCircuitBreakerStatus();
}
