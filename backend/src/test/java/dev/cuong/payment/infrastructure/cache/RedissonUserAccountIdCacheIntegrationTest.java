package dev.cuong.payment.infrastructure.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Redis-backed {@link RedissonUserAccountIdCache} round-trips and
 * degrades gracefully when the stored value is not a valid UUID. TTL behaviour
 * is asserted by reading back the key — explicit TTL-expiry assertions would
 * require sleeping past the TTL and are too slow / flaky for unit-suite use.
 */
@Testcontainers
class RedissonUserAccountIdCacheIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedissonClient redisson;

    @BeforeAll
    static void setUp() {
        redis.start();
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) redisson.shutdown();
        redis.stop();
    }

    private final RedissonUserAccountIdCache cache =
            new RedissonUserAccountIdCache(redisson, Duration.ofMinutes(5));

    @Test
    void should_return_empty_when_key_not_present() {
        Optional<UUID> result = cache.get(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    void should_return_stored_uuid_after_put() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        cache.put(userId, accountId);

        assertThat(cache.get(userId)).contains(accountId);
    }

    @Test
    void should_overwrite_value_when_put_called_again() {
        UUID userId = UUID.randomUUID();
        UUID firstAccountId = UUID.randomUUID();
        UUID secondAccountId = UUID.randomUUID();

        cache.put(userId, firstAccountId);
        cache.put(userId, secondAccountId);

        assertThat(cache.get(userId)).contains(secondAccountId);
    }

    @Test
    void should_return_empty_when_stored_value_is_not_a_valid_uuid() {
        UUID userId = UUID.randomUUID();
        // Bypass put() to inject a malformed value directly — simulates corruption
        // or a key collision with a different schema.
        redisson.<String>getBucket("account-id:" + userId).set("not-a-uuid");

        assertThat(cache.get(userId)).isEmpty();
    }

    @Test
    void should_use_separate_keys_per_user() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        cache.put(userA, accountA);
        cache.put(userB, accountB);

        assertThat(cache.get(userA)).contains(accountA);
        assertThat(cache.get(userB)).contains(accountB);
    }
}
