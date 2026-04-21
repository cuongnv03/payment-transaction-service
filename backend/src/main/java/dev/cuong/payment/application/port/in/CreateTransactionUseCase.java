package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.CreateTransactionCommand;
import dev.cuong.payment.application.dto.TransactionResult;

/**
 * Input port: create a new payment transaction with idempotency guarantee.
 *
 * <p>Callers must provide a unique {@code idempotencyKey} per logical operation.
 * Submitting the same key a second time returns the cached result without
 * re-executing the business logic.
 */
public interface CreateTransactionUseCase {

    TransactionResult createTransaction(CreateTransactionCommand command);
}
