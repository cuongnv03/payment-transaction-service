package dev.cuong.payment.infrastructure.ratelimit;

import dev.cuong.payment.application.port.out.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>{@link ObjectProvider} is used instead of {@code @ConditionalOnBean} to avoid the
 * bean-definition ordering problem: {@code @ConditionalOnBean} is evaluated during the
 * registration phase, before {@code @TestConfiguration} beans are registered, so it
 * would always fall back to no-op in tests. {@code ObjectProvider.getIfAvailable()}
 * is resolved at injection time, when all beans — including those from
 * {@code @TestConfiguration} — are already registered.
 *
 * <p>This is the "fail-open" resilience pattern: if the rate limiter infrastructure is
 * unavailable, we allow traffic rather than blocking everything.
 */
@Configuration
@Slf4j
public class RateLimiterConfig {

    @Bean
    public RateLimiter rateLimiter(
            ObjectProvider<RedissonClient> redissonClientProvider,
            @Value("${app.rate-limit.max-requests-per-minute:10}") int maxRequests,
            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient != null) {
            log.info("Rate limiter: Redis sliding-window (limit={}, window={}s)", maxRequests, windowSeconds);
            return new RedisRateLimiter(redissonClient, maxRequests, windowSeconds);
        }
        log.warn("Rate limiter: no-op (Redis unavailable — all requests allowed)");
        return userId -> true;
    }
}
