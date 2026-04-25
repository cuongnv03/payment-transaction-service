package dev.cuong.payment.infrastructure.gateway;

import dev.cuong.payment.application.port.out.PaymentGatewayPort;
import dev.cuong.payment.domain.exception.PaymentGatewayException;
import dev.cuong.payment.domain.exception.PaymentGatewayTimeoutException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

/**
 * Simulated payment gateway that produces realistic success/failure/timeout distributions.
 *
 * <p>The {@code @CircuitBreaker} and {@code @Retry} annotations are processed by
 * Resilience4j's Spring AOP aspect. The effective decoration order is:
 * <pre>CircuitBreaker { Retry { charge() } }</pre>
 * — if the circuit is OPEN, no retry is attempted (fast-fail). When CLOSED, up to
 * {@code max-attempts} retries fire on {@link PaymentGatewayTimeoutException};
 * {@link PaymentGatewayException} is ignored by the retry (permanent failure, fail immediately).
 *
 * <p>All rates are configurable via {@link GatewayProperties} / environment variables
 * so staging/load environments can dial specific failure scenarios.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MockPaymentGateway implements PaymentGatewayPort {

    private final GatewayProperties properties;
    private final Random random = new Random();

    @Override
    @CircuitBreaker(name = "payment-gateway")
    @Retry(name = "payment-gateway")
    public String charge(UUID transactionId, BigDecimal amount)
            throws PaymentGatewayException, PaymentGatewayTimeoutException {

        simulateLatency();

        double roll = random.nextDouble();

        if (roll < properties.getSuccessRate()) {
            String ref = "GW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            log.debug("Gateway accepted: transactionId={}, ref={}, amount={}", transactionId, ref, amount);
            return ref;
        }

        if (roll < properties.getSuccessRate() + properties.getFailRate()) {
            log.debug("Gateway rejected: transactionId={}, amount={}", transactionId, amount);
            throw new PaymentGatewayException("Payment rejected by gateway");
        }

        log.debug("Gateway timeout: transactionId={}", transactionId);
        throw new PaymentGatewayTimeoutException("Gateway did not respond in time");
    }

    private void simulateLatency() {
        int range = properties.getMaxDelayMs() - properties.getMinDelayMs();
        int delay = properties.getMinDelayMs() + (range > 0 ? random.nextInt(range) : 0);
        if (delay <= 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
