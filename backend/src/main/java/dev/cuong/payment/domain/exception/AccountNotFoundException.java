package dev.cuong.payment.domain.exception;

import java.util.UUID;

public class AccountNotFoundException extends DomainException {

    public AccountNotFoundException(UUID userId) {
        super("Account not found for user: " + userId);
    }
}
