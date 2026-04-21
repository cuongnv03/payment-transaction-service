package dev.cuong.payment.domain.exception;

import java.util.UUID;

public class TransactionNotFoundException extends DomainException {

    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found: " + transactionId);
    }
}
