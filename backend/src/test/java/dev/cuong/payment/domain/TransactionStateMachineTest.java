package dev.cuong.payment.domain;

import dev.cuong.payment.domain.exception.InvalidTransactionStateException;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionStateMachineTest {

    // ── Valid transitions ────────────────────────────────────────────────────

    @Test
    void should_transition_to_processing_when_pending() {
        Transaction tx = pendingTransaction();

        tx.startProcessing();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PROCESSING);
    }

    @Test
    void should_transition_to_success_and_record_gateway_ref_when_processing() {
        Transaction tx = pendingTransaction();
        tx.startProcessing();

        tx.complete("GW-REF-12345");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(tx.getGatewayReference()).isEqualTo("GW-REF-12345");
        assertThat(tx.getProcessedAt()).isNotNull();
    }

    @Test
    void should_transition_to_failed_and_record_reason_when_processing() {
        Transaction tx = pendingTransaction();
        tx.startProcessing();

        tx.fail("Payment gateway timeout");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureReason()).isEqualTo("Payment gateway timeout");
    }

    @Test
    void should_transition_to_timeout_when_processing() {
        Transaction tx = pendingTransaction();
        tx.startProcessing();

        tx.timeout();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.TIMEOUT);
    }

    @Test
    void should_transition_to_refunded_and_record_timestamp_when_success() {
        Transaction tx = pendingTransaction();
        tx.startProcessing();
        tx.complete("GW-REF-99");

        tx.refund();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(tx.getRefundedAt()).isNotNull();
    }

    // ── Invalid transitions ──────────────────────────────────────────────────

    @Test
    void should_reject_transition_from_pending_to_success() {
        Transaction tx = pendingTransaction();

        assertThatThrownBy(() -> tx.complete("GW-REF"))
                .isInstanceOf(InvalidTransactionStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("SUCCESS");
    }

    @Test
    void should_reject_refund_when_not_success() {
        Transaction tx = pendingTransaction();
        tx.startProcessing();
        tx.fail("Declined");

        assertThatThrownBy(tx::refund)
                .isInstanceOf(InvalidTransactionStateException.class)
                .hasMessageContaining("FAILED")
                .hasMessageContaining("REFUNDED");
    }

    @Test
    void should_reject_processing_when_already_processing() {
        Transaction tx = pendingTransaction();
        tx.startProcessing();

        assertThatThrownBy(tx::startProcessing)
                .isInstanceOf(InvalidTransactionStateException.class)
                .hasMessageContaining("PROCESSING");
    }

    @ParameterizedTest
    @EnumSource(value = TransactionStatus.class, names = {"FAILED", "TIMEOUT", "REFUNDED"})
    void should_reject_all_transitions_from_terminal_states(TransactionStatus terminal) {
        assertThatThrownBy(() -> terminal.transitionTo(TransactionStatus.PENDING))
                .isInstanceOf(InvalidTransactionStateException.class);
        assertThatThrownBy(() -> terminal.transitionTo(TransactionStatus.PROCESSING))
                .isInstanceOf(InvalidTransactionStateException.class);
    }

    // ── State introspection ──────────────────────────────────────────────────

    @Test
    void should_identify_terminal_states() {
        assertThat(TransactionStatus.FAILED.isTerminal()).isTrue();
        assertThat(TransactionStatus.TIMEOUT.isTerminal()).isTrue();
        assertThat(TransactionStatus.REFUNDED.isTerminal()).isTrue();
        assertThat(TransactionStatus.PENDING.isTerminal()).isFalse();
        assertThat(TransactionStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(TransactionStatus.SUCCESS.isTerminal()).isFalse();
    }

    @Test
    void should_flag_only_success_as_refundable() {
        assertThat(TransactionStatus.SUCCESS.canBeRefunded()).isTrue();
        assertThat(TransactionStatus.PENDING.canBeRefunded()).isFalse();
        assertThat(TransactionStatus.PROCESSING.canBeRefunded()).isFalse();
        assertThat(TransactionStatus.FAILED.canBeRefunded()).isFalse();
        assertThat(TransactionStatus.TIMEOUT.canBeRefunded()).isFalse();
        assertThat(TransactionStatus.REFUNDED.canBeRefunded()).isFalse();
    }

    @Test
    void should_expose_from_and_to_in_exception() {
        InvalidTransactionStateException ex = new InvalidTransactionStateException(
                TransactionStatus.FAILED, TransactionStatus.PENDING);

        assertThat(ex.getFrom()).isEqualTo(TransactionStatus.FAILED);
        assertThat(ex.getTo()).isEqualTo(TransactionStatus.PENDING);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Transaction pendingTransaction() {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .fromAccountId(UUID.randomUUID())
                .toAccountId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .status(TransactionStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

}
