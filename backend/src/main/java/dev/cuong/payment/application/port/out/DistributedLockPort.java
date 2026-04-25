package dev.cuong.payment.application.port.out;

/**
 * Output port: acquire and release a cluster-wide exclusive lock.
 *
 * <p>Implementations are expected to be backed by a distributed store (e.g., Redis)
 * so that multiple service instances coordinate on the same lock key.
 * The lock auto-expires after {@code leaseSeconds} as a safety net for process crashes.
 */
public interface DistributedLockPort {

    /**
     * Attempts to acquire an exclusive lock on {@code key}.
     *
     * @param key          lock identifier (should be unique per resource)
     * @param waitSeconds  maximum time to wait for the lock; 0 means return immediately
     * @param leaseSeconds automatic expiry time — the lock releases even if {@link #unlock}
     *                     is never called (crash safety)
     * @return {@code true} if the lock was acquired, {@code false} otherwise
     */
    boolean tryLock(String key, long waitSeconds, long leaseSeconds);

    /**
     * Releases the lock on {@code key}.
     * Safe to call even if the lock has already expired or was never acquired.
     */
    void unlock(String key);
}
