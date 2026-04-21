package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.PagedResult;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.domain.vo.TransactionStatus;

import java.util.UUID;

/**
 * Input port: read-side operations for transactions.
 *
 * <p>All queries are automatically scoped to the authenticated user's own account.
 * Attempting to access another user's transaction returns a 404 — we never reveal
 * whether a transaction with a given ID exists for a different account.
 */
public interface GetTransactionUseCase {

    /**
     * Returns the caller's own transactions, newest first, with optional status filter.
     *
     * @param userId the authenticated user's ID
     * @param status if non-null, restricts results to this status; if null, all statuses are returned
     * @param page   zero-based page index
     * @param size   maximum number of items per page (1–100)
     * @return paginated results scoped to the caller's account
     */
    PagedResult<TransactionResult> getMyTransactions(UUID userId, TransactionStatus status, int page, int size);

    /**
     * Returns a single transaction by ID, scoped to the caller's own account.
     * Throws {@link dev.cuong.payment.domain.exception.TransactionNotFoundException} — not a 403 —
     * when the transaction belongs to a different user, so we never reveal its existence.
     *
     * @param userId        the authenticated user's ID
     * @param transactionId the transaction to fetch
     */
    TransactionResult getMyTransaction(UUID userId, UUID transactionId);
}
