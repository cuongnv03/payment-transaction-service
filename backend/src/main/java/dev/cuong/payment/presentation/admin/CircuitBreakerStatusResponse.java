package dev.cuong.payment.presentation.admin;

public record CircuitBreakerStatusResponse(
        String name,
        String state,
        float failureRate,
        float slowCallRate,
        int bufferedCalls,
        int failedCalls
) {}
