package dev.cuong.payment.infrastructure.kafka;

import dev.cuong.payment.application.port.out.EventPublisher;
import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.domain.model.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;

/**
 * Kafka-backed implementation of {@link EventPublisher}.
 *
 * <p>Not annotated with {@code @Component} — instantiated by {@link KafkaConfig} so that
 * {@code ObjectProvider<KafkaTemplate>} can guard construction when Kafka is unavailable.
 *
 * <p>The {@code transactionId} is used as the Kafka partition key, which guarantees
 * that all events for the same transaction are delivered to the same partition and
 * therefore processed in the order they were published.
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(Transaction transaction, TransactionEventType eventType) {
        TransactionEventMessage message = new TransactionEventMessage(
                transaction.getId(),
                transaction.getFromAccountId(),
                transaction.getToAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name(),
                eventType,
                transaction.getDescription(),
                transaction.getGatewayReference(),
                transaction.getFailureReason(),
                transaction.getRetryCount(),
                Instant.now()
        );

        kafkaTemplate.send(topic, transaction.getId().toString(), message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event: transactionId={}, eventType={}, error={}",
                                transaction.getId(), eventType, ex.getMessage());
                    } else {
                        log.debug("Event delivered: transactionId={}, eventType={}, partition={}, offset={}",
                                transaction.getId(), eventType,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });

        log.info("Event published: transactionId={}, eventType={}, status={}",
                transaction.getId(), eventType, transaction.getStatus());
    }
}
