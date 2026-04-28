package dev.cuong.payment.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.AuditRepository;
import dev.cuong.payment.domain.model.Account;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka consumer that persists an immutable audit entry for every transaction
 * lifecycle event.
 *
 * <p><strong>Critical consumer:</strong> unlike the notification consumer, this consumer
 * re-throws all exceptions so the Kafka listener withholds the offset commit. The broker
 * re-delivers the message and {@link org.springframework.kafka.listener.DefaultErrorHandler}
 * retries up to the configured limit before routing to the DLQ (Task 15).
 *
 * <p><strong>Transaction semantics:</strong> {@code @Transactional} on the listener method
 * means the DB INSERT and the Kafka offset commit succeed or fail together — if the INSERT
 * fails the transaction rolls back, the offset is not committed, and Kafka retries.
 *
 * <p><strong>userId resolution:</strong> {@code TransactionEventMessage} carries
 * {@code fromAccountId} but not {@code userId}. The consumer loads the account from the DB
 * to obtain the owner's {@code userId}. Since {@code audit_logs.user_id} is {@code NOT NULL},
 * the account ID itself is used as a fallback if the account is unexpectedly absent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditConsumer {

    private final AuditRepository auditRepository;
    private final AccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.transaction-events}",
            groupId = "audit"
    )
    @Transactional
    public void onTransactionEvent(TransactionEventMessage event) {
        MDC.put("transactionId", event.transactionId().toString());
        try {
            UUID userId = resolveUserId(event);
            MDC.put("userId", userId.toString());

            String metadata = buildMetadata(event);

            auditRepository.record(new AuditRepository.AuditEntry(
                    event.transactionId(),
                    userId,
                    event.eventType().name(),
                    event.status(),
                    metadata
            ));

            log.info("[AUDIT] Recorded: eventType={}", event.eventType());

        } catch (Exception e) {
            log.error("[AUDIT] Failed to record event — will retry: eventType={}, error={}",
                    event.eventType(), e.getMessage(), e);
            throw e;
        } finally {
            MDC.remove("transactionId");
            MDC.remove("userId");
        }
    }

    private UUID resolveUserId(TransactionEventMessage event) {
        return accountRepository.findById(event.fromAccountId())
                .map(Account::getUserId)
                .orElseGet(() -> {
                    log.warn("[AUDIT] Account not found for fromAccountId={} — using accountId as userId proxy",
                            event.fromAccountId());
                    return event.fromAccountId();
                });
    }

    private String buildMetadata(TransactionEventMessage event) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("amount", event.amount());
        meta.put("currency", event.currency());
        meta.put("fromAccountId", event.fromAccountId());
        meta.put("toAccountId", event.toAccountId());
        if (event.description() != null)        meta.put("description", event.description());
        if (event.gatewayReference() != null)   meta.put("gatewayReference", event.gatewayReference());
        if (event.failureReason() != null)      meta.put("failureReason", event.failureReason());
        meta.put("retryCount", event.retryCount());

        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warn("[AUDIT] Could not serialize metadata for transactionId={} — storing empty object",
                    event.transactionId());
            return "{}";
        }
    }
}
