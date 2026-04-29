package dev.cuong.payment.infrastructure.cache;

import dev.cuong.payment.application.port.out.UserAccountIdCache;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Wires {@link UserAccountIdCache} to either:
 *
 * <ul>
 *   <li>{@link RedissonUserAccountIdCache} — when a {@link RedissonClient} bean
 *       is present (production, integration tests with Redis container).</li>
 *   <li>A no-op cache — when Redis is unavailable (tests that exclude Redisson).
 *       Get always misses, put is discarded; the application service falls
 *       through to the DB on every call. Correct, just unoptimised.</li>
 * </ul>
 *
 * <p>Mirrors the rate-limiter / distributed-lock fail-open pattern used elsewhere.
 */
@Configuration
@Slf4j
public class UserAccountIdCacheConfig {

    private static final Duration TTL = Duration.ofHours(1);

    @Bean
    @ConditionalOnBean(RedissonClient.class)
    UserAccountIdCache redissonUserAccountIdCache(RedissonClient redisson) {
        log.info("UserAccountId cache: Redis (TTL={})", TTL);
        return new RedissonUserAccountIdCache(redisson, TTL);
    }

    @Bean
    @ConditionalOnMissingBean(UserAccountIdCache.class)
    UserAccountIdCache noOpUserAccountIdCache() {
        log.warn("UserAccountId cache: no-op (Redis unavailable — every dashboard request will hit the DB to resolve account ID)");
        return new UserAccountIdCache() {
            @Override public Optional<UUID> get(UUID userId) { return Optional.empty(); }
            @Override public void put(UUID userId, UUID accountId) { /* no-op */ }
        };
    }
}
