package dev.cuong.payment.application.port.out;

import dev.cuong.payment.domain.model.Account;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port: persistence operations for {@link Account} aggregates.
 *
 * <p>{@link #findByUserIdForUpdate} must be called within an active transaction
 * (application service boundary). The implementation acquires a pessimistic write
 * lock on the row to prevent concurrent balance corruption.
 */
public interface AccountRepository {

    Optional<Account> findById(UUID accountId);

    Optional<Account> findByUserId(UUID userId);

    /**
     * Finds the account for the given user and acquires a pessimistic write lock
     * (SELECT FOR UPDATE) for the duration of the surrounding transaction.
     * Use this when the caller intends to modify the balance.
     */
    Optional<Account> findByUserIdForUpdate(UUID userId);

    /**
     * Finds the account by its own ID and acquires a pessimistic write lock.
     * Used by the Kafka consumer which has an {@code accountId} from the event message,
     * not a {@code userId} from the JWT context.
     */
    Optional<Account> findByIdForUpdate(UUID accountId);

    Account save(Account account);
}
