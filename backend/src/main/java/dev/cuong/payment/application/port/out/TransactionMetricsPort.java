package dev.cuong.payment.application.port.out;

import java.time.Duration;

/**
 * Outbound port for emitting business metrics about transaction processing.
 *
 * <p>The application layer depends on this port — not on Micrometer or any specific
 * monitoring SDK — so the metrics backend can be swapped (Prometheus, OpenTelemetry,
 * Datadog, …) without touching business code.
 *
 * <p>Built-in HTTP/JVM/DB metrics are auto-instrumented by Spring Boot Actuator and
 * are not represented here. This port is for <em>business</em> meters only.
 */
public interface TransactionMetricsPort {

    /**
     * Increments the {@code transactions.created.total} counter.
     * Called once per successful transaction creation in the API path.
     */
    void recordCreated();

    /**
     * Records the outcome of a processed transaction, both as a counter increment
     * (tagged by {@code status}) and as a duration sample on the latency histogram.
     *
     * @param finalStatus terminal status name: {@code SUCCESS}, {@code FAILED}, or {@code TIMEOUT}
     * @param duration    wall-clock processing time from PENDING→terminal
     */
    void recordProcessed(String finalStatus, Duration duration);
}
