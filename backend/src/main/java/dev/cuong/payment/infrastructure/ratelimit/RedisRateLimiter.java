package dev.cuong.payment.infrastructure.ratelimit;

import dev.cuong.payment.application.port.out.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.UUID;

/**
 * Redis sliding-window rate limiter backed by a Lua script for atomicity.
 *
 * <p>Key: {@code rate_limit:tx:{userId}} — one sorted set per user.
 * Score = request timestamp (ms); member = unique request UUID.
 * The Lua script is sent to Redis and executed as a single indivisible command,
 * preventing any race between the count check and the ZADD.
 *
 * <p>Declared as a Spring bean via {@link RateLimiterConfig}, not directly annotated
 * with {@code @Component}, so that {@code @ConditionalOnBean(RedissonClient.class)}
 * can be applied at the configuration class level where evaluation order is reliable.
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "rate_limit:tx:";

    /**
     * Sliding-window Lua script.
     *
     * KEYS[1] = rate limit key for the user
     * ARGV[1] = current timestamp in milliseconds
     * ARGV[2] = window size in milliseconds
     * ARGV[3] = max allowed requests per window
     * ARGV[4] = unique member for this request (prevents score collisions)
     *
     * Returns 1 if the request is allowed, 0 if the limit is exceeded.
     */
    private static final String SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window_ms)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('EXPIRE', key, math.ceil(window_ms / 1000) + 10)
            return 1
            """;

    private final RedissonClient redissonClient;
    private final int maxRequests;
    private final long windowSeconds;

    public RedisRateLimiter(RedissonClient redissonClient, int maxRequests, long windowSeconds) {
        this.redissonClient = redissonClient;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public boolean tryConsume(UUID userId) {
        String key = KEY_PREFIX + userId;
        long nowMs = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000;
        String member = UUID.randomUUID().toString();

        // StringCodec sends args as raw UTF-8 strings so tonumber() in the Lua script works.
        // Redisson's default codec serializes values as binary, which Lua cannot parse numerically.
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        Long result = script.eval(
                RScript.Mode.READ_WRITE,
                SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(key),
                String.valueOf(nowMs),
                String.valueOf(windowMs),
                String.valueOf(maxRequests),
                member);

        boolean allowed = Long.valueOf(1L).equals(result);
        if (!allowed) {
            log.warn("Rate limit exceeded: userId={}, limit={}/{}s", userId, maxRequests, windowSeconds);
        }
        return allowed;
    }
}
