package dev.cuong.payment.infrastructure.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externally-configurable parameters for {@link MockPaymentGateway}.
 *
 * <p>In production, point {@code successRate} and related rates at environment variables
 * so that staging/canary environments can simulate realistic failure scenarios.
 */
@Component
@ConfigurationProperties(prefix = "app.gateway")
@Getter
@Setter
public class GatewayProperties {

    /** Probability [0, 1] that a charge succeeds. */
    private double successRate = 0.8;

    /** Probability [0, 1] that the gateway returns a permanent failure. */
    private double failRate = 0.1;

    /** Probability [0, 1] that the gateway times out (transient — retryable). */
    private double timeoutRate = 0.1;

    /** Minimum simulated processing delay in milliseconds. */
    private int minDelayMs = 100;

    /** Maximum simulated processing delay in milliseconds. */
    private int maxDelayMs = 500;
}
