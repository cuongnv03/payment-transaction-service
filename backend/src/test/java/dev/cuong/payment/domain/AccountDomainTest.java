package dev.cuong.payment.domain;

import dev.cuong.payment.domain.exception.InsufficientFundsException;
import dev.cuong.payment.domain.model.Account;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDomainTest {

    // ── Debit ────────────────────────────────────────────────────────────────

    @Test
    void should_reduce_balance_when_debiting_valid_amount() {
        Account account = accountWithBalance("500.00");

        account.debit(new BigDecimal("200.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("300.00");
    }

    @Test
    void should_allow_debiting_entire_balance() {
        Account account = accountWithBalance("100.00");

        account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void should_throw_when_debit_amount_exceeds_balance() {
        Account account = accountWithBalance("100.00");

        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void should_include_balance_and_requested_in_exception() {
        UUID userId = UUID.randomUUID();
        Account account = accountWithUserId(userId, "50.00");

        InsufficientFundsException ex = (InsufficientFundsException)
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> account.debit(new BigDecimal("75.00")));

        assertThat(ex.getUserId()).isEqualTo(userId);
        assertThat(ex.getBalance()).isEqualByComparingTo("50.00");
        assertThat(ex.getRequested()).isEqualByComparingTo("75.00");
    }

    @Test
    void should_update_timestamp_after_debit() {
        Account account = accountWithBalance("200.00");
        Instant before = Instant.now();

        account.debit(new BigDecimal("50.00"));

        assertThat(account.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    // ── Credit ───────────────────────────────────────────────────────────────

    @Test
    void should_increase_balance_when_crediting() {
        Account account = accountWithBalance("100.00");

        account.credit(new BigDecimal("50.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void should_update_timestamp_after_credit() {
        Account account = accountWithBalance("100.00");
        Instant before = Instant.now();

        account.credit(new BigDecimal("25.00"));

        assertThat(account.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Account accountWithBalance(String balance) {
        return accountWithUserId(UUID.randomUUID(), balance);
    }

    private Account accountWithUserId(UUID userId, String balance) {
        return Account.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .balance(new BigDecimal(balance))
                .currency("USD")
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
