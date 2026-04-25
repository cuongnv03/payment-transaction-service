package dev.cuong.payment.domain.exception;

/**
 * The external payment gateway did not respond within the expected window.
 *
 * <p>This is a transient failure — the gateway may succeed on a subsequent attempt.
 * Resilience4j retry is configured to <em>retry</em> this exception class (up to 3 times
 * with exponential backoff: 1 s → 2 s → 4 s). After all retries are exhausted the
 * exception propagates and the transaction is marked {@code TIMEOUT}.
 */
public class PaymentGatewayTimeoutException extends RuntimeException {

    public PaymentGatewayTimeoutException(String message) {
        super(message);
    }
}
