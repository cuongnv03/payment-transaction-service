package dev.cuong.payment.application.port.out;

import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port: persistence operations for {@link Transaction} aggregates.
 *
 * <p>All list/count methods are scoped to {@code fromAccountId} (the sender's account)
 * so that callers never accidentally expose another user's transactions.
 */
public interface TransactionRepository {

    Optional<Transaction> findById(UUID id);

    /** Returns the transaction only if it belongs to the given sender account — prevents cross-user access. */
    Optional<Transaction> findByIdAndFromAccountId(UUID id, UUID fromAccountId);

    List<Transaction> findByFromAccountId(UUID fromAccountId, int page, int size);

    List<Transaction> findByFromAccountIdAndStatus(UUID fromAccountId, TransactionStatus status, int page, int size);

    long countByFromAccountId(UUID fromAccountId);

    long countByFromAccountIdAndStatus(UUID fromAccountId, TransactionStatus status);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Transaction save(Transaction transaction);
}
