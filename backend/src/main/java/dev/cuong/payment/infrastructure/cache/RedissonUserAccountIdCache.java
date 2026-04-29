package dev.cuong.payment.infrastructure.cache;

import dev.cuong.payment.application.port.out.UserAccountIdCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed cache for {@code userId → accountId}.
 *
 * <p>Stores values as plain stringified UUIDs in Redisson buckets. Keys are
 * namespaced under {@code account-id:} so they don't collide with other
 * Redis-stored data (rate limit, distributed locks).
 *
 * <p>Misses, parse errors, and Redis-side exceptions all degrade gracefully:
 * {@link #get} returns {@link Optional#empty()} so the application falls back
 * to the DB. {@link #put} swallows exceptions because cache failures must not
 * affect the request that called it.
 */
@RequiredArgsConstructor
@Slf4j
public class RedissonUserAccountIdCache implements UserAccountIdCache {

    private static final String KEY_PREFIX = "account-id:";

    private final RedissonClient redisson;
    private final Duration ttl;

    @Override
    public Optional<UUID> get(UUID userId) {
        try {
            RBucket<String> bucket = redisson.getBucket(key(userId));
            String stored = bucket.get();
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(stored));
        } catch (IllegalArgumentException badUuid) {
            // Stored value is not a valid UUID — treat as miss; the caller will
            // overwrite via put() after the DB lookup.
            log.warn("[CACHE] Invalid UUID in cache for userId={}: {}", userId, badUuid.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[CACHE] Redis get failed for userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID userId, UUID accountId) {
        try {
            RBucket<String> bucket = redisson.getBucket(key(userId));
            bucket.set(accountId.toString(), ttl);
        } catch (Exception e) {
            // Cache writes are best-effort; never let them fail the calling request.
            log.warn("[CACHE] Redis put failed for userId={}: {}", userId, e.getMessage());
        }
    }

    private static String key(UUID userId) {
        return KEY_PREFIX + userId;
    }
}
