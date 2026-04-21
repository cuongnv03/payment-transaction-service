package dev.cuong.payment.application.port.out;

import java.util.UUID;

/**
 * Output port: rate-limiting gate for transaction creation.
 *
 * <p>Each call consumes one slot in the user's sliding-window bucket.
 * Implementations must be thread-safe and atomic — concurrent calls from
 * the same user must not allow more than the configured limit through.
 */
public interface RateLimiter {

    /**
     * Attempts to consume one request slot for the given user.
     *
     * @param userId the authenticated user's ID
     * @return {@code true} if the request is within the allowed rate, {@code false} if the limit is exceeded
     */
    boolean tryConsume(UUID userId);
}
