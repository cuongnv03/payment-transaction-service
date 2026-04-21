package dev.cuong.payment.infrastructure.ratelimit;

import dev.cuong.payment.application.port.out.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the appropriate {@link RateLimiter} bean depending on whether Redis is available.
 *
 * <p>When {@link RedissonClient} is present (production + rate-limit integration tests),
 * the full sliding-window implementation is used. When Redisson is excluded (unit and
 * service integration tests that exclude Redis), a no-op implementation is used so
 * those tests don't require a Redis container and the application context still starts.
 *
 * <p>This is the "fail-open" resilience pattern: if the rate limiter infrastructure is
 * unavailable, we allow traffic rather than blocking everything. In production, a
 * Resilience4j circuit breaker on the Redis calls can add a second safety layer.
 */
@Configuration
@Slf4j
public class RateLimiterConfig {

    @Bean
    @ConditionalOnBean(RedissonClient.class)
    public RateLimiter redisRateLimiter(
            RedissonClient redissonClient,
            @Value("${app.rate-limit.max-requests-per-minute:10}") int maxRequests,
            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
        log.info("Rate limiter: Redis sliding-window (limit={}, window={}s)", maxRequests, windowSeconds);
        return new RedisRateLimiter(redissonClient, maxRequests, windowSeconds);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter noOpRateLimiter() {
        log.warn("Rate limiter: no-op (Redis unavailable — all requests allowed)");
        return userId -> true;
    }
}
