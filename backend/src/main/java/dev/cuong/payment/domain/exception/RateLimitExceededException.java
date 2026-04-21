package dev.cuong.payment.domain.exception;

import java.util.UUID;

public class RateLimitExceededException extends DomainException {

    public RateLimitExceededException(UUID userId) {
        super("Rate limit exceeded for user: " + userId + ". Max 10 requests per 60 seconds.");
    }
}
