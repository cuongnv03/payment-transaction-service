package dev.cuong.payment.domain.exception;

import java.util.UUID;

public class SameAccountTransferException extends DomainException {

    public SameAccountTransferException(UUID accountId) {
        super("Cannot transfer to the same account: " + accountId);
    }
}
