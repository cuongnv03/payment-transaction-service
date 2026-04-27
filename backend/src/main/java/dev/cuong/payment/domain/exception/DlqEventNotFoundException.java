package dev.cuong.payment.domain.exception;

import java.util.UUID;

public class DlqEventNotFoundException extends DomainException {

    public DlqEventNotFoundException(UUID dlqEventId) {
        super("Dead-letter event not found: " + dlqEventId);
    }
}
