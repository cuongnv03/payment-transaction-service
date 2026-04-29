package dev.cuong.payment.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: caches the {@code userId → accountId} resolution that every
 * dashboard read path performs. The mapping is effectively immutable — accounts
 * are created once at user registration and never deleted in this codebase —
 * so the cache lives with a long TTL and no explicit invalidation.
 *
 * <p>A miss returns {@link Optional#empty()}; the caller falls through to the
 * authoritative {@code AccountRepository} lookup and writes the result back to
 * the cache via {@link #put}.
 *
 * <p>The implementation may be a no-op when the cache backend is unavailable
 * (e.g. Redis down, or in tests that exclude Redisson) — in that case
 * {@link #get} always returns empty and {@link #put} is a no-op. The system
 * remains correct, just runs without the optimisation.
 */
public interface UserAccountIdCache {

    /** Returns the cached account ID for the given user, or empty on miss. */
    Optional<UUID> get(UUID userId);

    /** Stores the {@code userId → accountId} mapping with the configured TTL. */
    void put(UUID userId, UUID accountId);
}
