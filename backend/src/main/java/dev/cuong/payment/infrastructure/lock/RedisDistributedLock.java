package dev.cuong.payment.infrastructure.lock;

import dev.cuong.payment.application.port.out.DistributedLockPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed distributed lock using Redisson's {@link RLock}.
 *
 * <p>Not annotated with {@code @Component} — created by {@link DistributedLockConfig}
 * via {@code ObjectProvider<RedissonClient>} so that a no-op fallback can be provided
 * when Redis is unavailable (same pattern as {@code RateLimiterConfig}).
 *
 * <p>The {@code leaseSeconds} parameter is critical: if the holding process crashes before
 * calling {@link #unlock}, Redis automatically releases the lock after the lease expires,
 * preventing deadlock. Set it to slightly longer than the maximum expected processing time.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisDistributedLock implements DistributedLockPort {

    private final RedissonClient redissonClient;

    @Override
    public boolean tryLock(String key, long waitSeconds, long leaseSeconds) {
        try {
            RLock lock = redissonClient.getLock(key);
            boolean acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("Lock not acquired: key={}", key);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring lock: key={}", key);
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        try {
            RLock lock = redissonClient.getLock(key);
            // isHeldByCurrentThread() prevents IllegalMonitorStateException when the
            // lease has already expired or the lock was never acquired by this thread.
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("Failed to release lock: key={}, error={}", key, e.getMessage());
        }
    }
}
