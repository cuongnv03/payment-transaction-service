package dev.cuong.payment.application.port.out;

import dev.cuong.payment.domain.exception.PaymentGatewayException;
import dev.cuong.payment.domain.exception.PaymentGatewayTimeoutException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Output port: submit a payment charge to the external payment gateway.
 *
 * <p>Implementations are responsible for Resilience4j circuit-breaker and retry
 * decoration. Callers should treat the thrown exceptions as described:
 * <ul>
 *   <li>{@link PaymentGatewayException} — permanent rejection; do not retry; mark FAILED.
 *   <li>{@link PaymentGatewayTimeoutException} — transient failure; retry is handled by
 *       the implementation; if still thrown after retries, mark TIMEOUT.
 * </ul>
 */
public interface PaymentGatewayPort {

    /**
     * Charges the given amount through the payment gateway.
     *
     * @param transactionId used as a gateway-level idempotency key
     * @param amount        amount to charge, always positive
     * @return gateway reference string on success
     * @throws PaymentGatewayException        if the gateway permanently rejects the charge
     * @throws PaymentGatewayTimeoutException if the gateway does not respond in time
     */
    String charge(UUID transactionId, BigDecimal amount)
            throws PaymentGatewayException, PaymentGatewayTimeoutException;
}
