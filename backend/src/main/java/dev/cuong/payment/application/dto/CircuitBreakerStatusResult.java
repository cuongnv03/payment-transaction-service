package dev.cuong.payment.application.dto;

/**
 * Snapshot of the {@code payment-gateway} circuit breaker state.
 * Passed from the admin service to the presentation layer.
 *
 * @param name            circuit breaker name
 * @param state           current state: CLOSED, OPEN, HALF_OPEN, DISABLED, or FORCED_OPEN
 * @param failureRate     percentage of failed calls in the sliding window (-1 if not enough calls yet)
 * @param slowCallRate    percentage of slow calls in the sliding window (-1 if not enough calls yet)
 * @param bufferedCalls   number of calls recorded in the sliding window
 * @param failedCalls     number of failed calls in the sliding window
 */
public record CircuitBreakerStatusResult(
        String name,
        String state,
        float failureRate,
        float slowCallRate,
        int bufferedCalls,
        int failedCalls
) {}
