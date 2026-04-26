package dev.cuong.payment.infrastructure.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that dispatches simulated notifications (email / SMS) for every
 * transaction lifecycle event.
 *
 * <p>Consumer group {@code notification} maintains its own independent offset from
 * {@code payment-processor} — every message on {@code payment.transaction.events} is
 * delivered to both groups regardless of the other group's lag or state.
 *
 * <p>In production this class would delegate to a {@code NotificationPort} output port
 * (e.g. an email/SMS gateway). For the portfolio implementation, structured log statements
 * simulate the dispatched notifications without making real external calls.
 */
@Component
@Slf4j
public class NotificationConsumer {

    @KafkaListener(
            topics = "${app.kafka.topics.transaction-events}",
            groupId = "notification"
    )
    public void onTransactionEvent(TransactionEventMessage event) {
        switch (event.eventType()) {
            case CREATED    -> notifyInitiated(event);
            case PROCESSING -> notifyProcessing(event);
            case SUCCESS    -> notifySucceeded(event);
            case FAILED     -> notifyFailed(event);
            case TIMEOUT    -> notifyTimedOut(event);
            case REFUNDED   -> notifyRefunded(event);
        }
    }

    private void notifyInitiated(TransactionEventMessage e) {
        log.info("[NOTIFY] email→sender: Payment of {} {} initiated — awaiting processing. " +
                        "transactionId={}, toAccount={}",
                e.amount(), e.currency(), e.transactionId(), e.toAccountId());
    }

    private void notifyProcessing(TransactionEventMessage e) {
        log.info("[NOTIFY] email→sender: Your payment is being processed by the gateway. " +
                        "transactionId={}",
                e.transactionId());
    }

    private void notifySucceeded(TransactionEventMessage e) {
        log.info("[NOTIFY] email→sender: Payment of {} {} completed successfully. " +
                        "email→receiver: {} {} has been deposited to your account. " +
                        "transactionId={}, gatewayRef={}",
                e.amount(), e.currency(),
                e.amount(), e.currency(),
                e.transactionId(), e.gatewayReference());
    }

    private void notifyFailed(TransactionEventMessage e) {
        log.info("[NOTIFY] email→sender: Payment of {} {} could not be processed — " +
                        "your funds have been returned. transactionId={}, reason={}",
                e.amount(), e.currency(), e.transactionId(), e.failureReason());
    }

    private void notifyTimedOut(TransactionEventMessage e) {
        log.info("[NOTIFY] email→sender: Payment of {} {} timed out — " +
                        "your funds have been returned. transactionId={}",
                e.amount(), e.currency(), e.transactionId());
    }

    private void notifyRefunded(TransactionEventMessage e) {
        log.info("[NOTIFY] email→sender: Refund of {} {} has been processed. " +
                        "email→receiver: {} {} has been deducted following a refund. " +
                        "transactionId={}",
                e.amount(), e.currency(),
                e.amount(), e.currency(),
                e.transactionId());
    }
}
