package dev.cuong.payment.domain.exception;

import dev.cuong.payment.domain.vo.TransactionStatus;

public class InvalidTransactionStateException extends DomainException {

    private final TransactionStatus from;
    private final TransactionStatus to;

    public InvalidTransactionStateException(TransactionStatus from, TransactionStatus to) {
        super(String.format("Cannot transition transaction from %s to %s", from, to));
        this.from = from;
        this.to = to;
    }

    public TransactionStatus getFrom() {
        return from;
    }

    public TransactionStatus getTo() {
        return to;
    }
}
