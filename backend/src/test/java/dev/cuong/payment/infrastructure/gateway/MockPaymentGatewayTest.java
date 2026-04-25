package dev.cuong.payment.infrastructure.gateway;

import dev.cuong.payment.domain.exception.PaymentGatewayException;
import dev.cuong.payment.domain.exception.PaymentGatewayTimeoutException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MockPaymentGateway}.
 *
 * <p>Tested without Spring context — {@code @CircuitBreaker} and {@code @Retry} AOP aspects
 * are inactive here. Each test configures a deterministic {@link GatewayProperties} so that
 * the probability-based outcome is fully controlled.
 */
class MockPaymentGatewayTest {

    private static GatewayProperties propsFor(double success, double fail, double timeout) {
        GatewayProperties p = new GatewayProperties();
        p.setSuccessRate(success);
        p.setFailRate(fail);
        p.setTimeoutRate(timeout);
        p.setMinDelayMs(0);
        p.setMaxDelayMs(0);
        return p;
    }

    // ── Deterministic outcomes ────────────────────────────────────────────────

    @Test
    void should_return_gateway_reference_when_success_rate_is_1() throws Exception {
        MockPaymentGateway gateway = new MockPaymentGateway(propsFor(1.0, 0.0, 0.0));

        String ref = gateway.charge(UUID.randomUUID(), new BigDecimal("100.00"));

        assertThat(ref).startsWith("GW-");
        assertThat(ref).hasSize(13); // "GW-" + 10 hex chars
    }

    @Test
    void should_throw_gateway_exception_when_fail_rate_is_1() {
        MockPaymentGateway gateway = new MockPaymentGateway(propsFor(0.0, 1.0, 0.0));

        assertThatThrownBy(() -> gateway.charge(UUID.randomUUID(), BigDecimal.TEN))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void should_throw_timeout_exception_when_timeout_rate_is_1() {
        MockPaymentGateway gateway = new MockPaymentGateway(propsFor(0.0, 0.0, 1.0));

        assertThatThrownBy(() -> gateway.charge(UUID.randomUUID(), BigDecimal.TEN))
                .isInstanceOf(PaymentGatewayTimeoutException.class);
    }

    // ── Gateway reference format ──────────────────────────────────────────────

    @Test
    void should_generate_unique_reference_per_successful_charge() throws Exception {
        MockPaymentGateway gateway = new MockPaymentGateway(propsFor(1.0, 0.0, 0.0));

        String ref1 = gateway.charge(UUID.randomUUID(), BigDecimal.TEN);
        String ref2 = gateway.charge(UUID.randomUUID(), BigDecimal.TEN);

        assertThat(ref1).isNotEqualTo(ref2);
    }

    // ── Probability distribution ──────────────────────────────────────────────

    @Test
    void should_produce_expected_outcome_distribution_over_many_calls() {
        MockPaymentGateway gateway = new MockPaymentGateway(propsFor(0.7, 0.2, 0.1));

        int successes = 0;
        int failures  = 0;
        int timeouts  = 0;
        int total = 10_000;

        for (int i = 0; i < total; i++) {
            try {
                gateway.charge(UUID.randomUUID(), BigDecimal.TEN);
                successes++;
            } catch (PaymentGatewayException e) {
                failures++;
            } catch (PaymentGatewayTimeoutException e) {
                timeouts++;
            }
        }

        // Allow ±5% tolerance around configured rates
        double successPct = (double) successes / total;
        double failPct    = (double) failures  / total;
        double timeoutPct = (double) timeouts  / total;

        assertThat(successPct).isBetween(0.65, 0.75);
        assertThat(failPct)   .isBetween(0.15, 0.25);
        assertThat(timeoutPct).isBetween(0.05, 0.15);
    }

    // ── No latency when min/max delay are zero ────────────────────────────────

    @Test
    void should_complete_without_delay_when_min_and_max_delay_are_zero() throws Exception {
        MockPaymentGateway gateway = new MockPaymentGateway(propsFor(1.0, 0.0, 0.0));

        long start = System.currentTimeMillis();
        gateway.charge(UUID.randomUUID(), BigDecimal.TEN);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(500);
    }

    // ── Multiple amounts handled ──────────────────────────────────────────────

    @Test
    void should_accept_varying_amounts() {
        MockPaymentGateway gateway = new MockPaymentGateway(propsFor(1.0, 0.0, 0.0));
        List<BigDecimal> amounts = List.of(
                new BigDecimal("0.01"),
                new BigDecimal("999999.99"),
                new BigDecimal("100.00")
        );

        amounts.forEach(amount -> {
            try {
                assertThat(gateway.charge(UUID.randomUUID(), amount)).isNotBlank();
            } catch (Exception e) {
                throw new AssertionError("Unexpected exception for amount " + amount, e);
            }
        });
    }
}
