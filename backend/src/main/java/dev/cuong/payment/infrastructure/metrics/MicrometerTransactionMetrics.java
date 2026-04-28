package dev.cuong.payment.infrastructure.metrics;

import dev.cuong.payment.application.port.out.TransactionMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Micrometer-backed implementation of {@link TransactionMetricsPort}.
 *
 * <p>Meters exposed at {@code /actuator/prometheus}:
 * <ul>
 *   <li>{@code transactions_created_total} — counter, no tags
 *   <li>{@code transactions_processed_total{status="SUCCESS|FAILED|TIMEOUT"}} — counter
 *   <li>{@code transactions_processing_duration_seconds} — timer (with P50/P95/P99 histogram)
 * </ul>
 *
 * <p>Per-status counters are cached in a {@link ConcurrentMap} so we register each
 * meter exactly once with the registry — registering on every call would create a
 * new meter object per invocation (Micrometer deduplicates internally, but caching
 * skips the lookup overhead on the hot path).
 */
@Component
public class MicrometerTransactionMetrics implements TransactionMetricsPort {

    private static final String CREATED_COUNTER = "transactions.created";
    private static final String PROCESSED_COUNTER = "transactions.processed";
    private static final String PROCESSING_TIMER = "transactions.processing.duration";

    private final MeterRegistry registry;
    private final Counter createdCounter;
    private final Timer processingTimer;
    private final ConcurrentMap<String, Counter> processedCountersByStatus = new ConcurrentHashMap<>();

    public MicrometerTransactionMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.createdCounter = Counter.builder(CREATED_COUNTER)
                .description("Total transactions created via the API")
                .register(registry);

        this.processingTimer = Timer.builder(PROCESSING_TIMER)
                .description("End-to-end processing duration: PENDING → terminal status")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Override
    public void recordCreated() {
        createdCounter.increment();
    }

    @Override
    public void recordProcessed(String finalStatus, Duration duration) {
        processedCountersByStatus
                .computeIfAbsent(finalStatus, this::buildProcessedCounter)
                .increment();
        processingTimer.record(duration);
    }

    private Counter buildProcessedCounter(String status) {
        return Counter.builder(PROCESSED_COUNTER)
                .description("Total transactions processed, tagged by terminal status")
                .tag("status", status)
                .register(registry);
    }
}
