package dev.cuong.payment.infrastructure.lock;

import dev.cuong.payment.application.port.out.DistributedLockPort;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the appropriate {@link DistributedLockPort} depending on Redis availability.
 *
 * <p>Uses the same {@link ObjectProvider} pattern as {@code RateLimiterConfig}: resolves
 * at injection time so the test's {@code @TestConfiguration} Redis bean is visible.
 * When Redis is absent, a no-op lock (always acquires) is used — the JPA optimistic lock
 * on the transaction entity acts as the safety net against double-processing.
 */
@Configuration
@Slf4j
public class DistributedLockConfig {

    @Bean
    public DistributedLockPort distributedLockPort(ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client != null) {
            log.info("Distributed lock: Redis (Redisson)");
            return new RedisDistributedLock(client);
        }
        log.warn("Distributed lock: no-op (Redis unavailable — optimistic lock is the only guard)");
        return new DistributedLockPort() {
            @Override
            public boolean tryLock(String key, long waitSeconds, long leaseSeconds) {
                return true;
            }

            @Override
            public void unlock(String key) {
            }
        };
    }
}
