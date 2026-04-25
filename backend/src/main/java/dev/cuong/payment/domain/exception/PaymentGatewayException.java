package dev.cuong.payment.domain.exception;

/**
 * The external payment gateway explicitly rejected the charge.
 *
 * <p>This is a permanent business failure — retrying the same request will yield the
 * same rejection (e.g., card declined, fraud block, account closed). The transaction
 * should be marked {@code FAILED} and the sender's balance restored immediately.
 *
 * <p>Resilience4j retry is configured to <em>ignore</em> this exception class, so
 * no retry is attempted. The circuit breaker still records it as a failure.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String reason) {
        super(reason);
    }
}
