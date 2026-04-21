package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.TransactionResult;

import java.util.UUID;

/**
 * Input port: initiate a refund for a completed P2P transaction.
 *
 * <p>Refunds are only allowed from {@code SUCCESS} status. Any other status
 * results in a 409 Conflict. The caller must own the transaction (fromAccount
 * belongs to them); otherwise a 404 is returned so existence is not revealed.
 *
 * <p>The sender's account is credited immediately. The receiver-side deduction
 * (taking money back from the recipient) is handled by the downstream consumer
 * in Task 12 once domain events are wired in.
 */
public interface RefundTransactionUseCase {

    /**
     * @param userId        the authenticated user's ID — must own the transaction
     * @param transactionId the transaction to refund
     * @return the updated transaction in REFUNDED status
     */
    TransactionResult refundTransaction(UUID userId, UUID transactionId);
}
