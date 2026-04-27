package dev.cuong.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.DeadLetterRepository;
import dev.cuong.payment.application.port.out.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topic declarations and {@link EventPublisher} wiring.
 *
 * <p>{@code KafkaAdmin} reads the {@link NewTopic} beans on startup and creates any
 * topics that do not yet exist — idempotent and safe to run repeatedly.
 *
 * <p>The {@link EventPublisher} bean uses {@code ObjectProvider<KafkaTemplate>} (raw type)
 * so that the generic parameter mismatch between the auto-configured
 * {@code KafkaTemplate<Object,Object>} and {@code KafkaTemplate<String,Object>} is resolved
 * at runtime via type erasure. When {@code KafkaAutoConfiguration} is excluded in tests,
 * {@code getIfAvailable()} returns {@code null} and a no-op lambda is registered instead —
 * all existing persistence/rate-limit tests remain green without modification.
 */
@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${app.kafka.topics.transaction-events}")
    private String transactionEventsTopic;

    @Value("${app.kafka.topics.transaction-dlq}")
    private String transactionDlqTopic;

    @Bean
    public NewTopic transactionEventsTopic() {
        return TopicBuilder.name(transactionEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transactionDlqTopic() {
        return TopicBuilder.name(transactionDlqTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Error handler that retries failed consumer records up to 2 times (1-second delay)
     * and then routes the message to the DLQ.
     *
     * <p>On exhaustion the {@link PersistingDlqRecoverer} (a) persists the raw payload and
     * error details to {@code dead_letter_events}, then (b) publishes the record to the Kafka
     * DLQ topic via {@link DeadLetterPublishingRecoverer}. DB is written first: if Kafka publish
     * fails the admin still sees the entry in the DB and can retry manually.
     *
     * <p>Falls back to a logging-only handler when {@code KafkaTemplate} is unavailable
     * (tests that exclude {@code KafkaAutoConfiguration}).
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            @SuppressWarnings("rawtypes") ObjectProvider<KafkaTemplate> kafkaTemplateProvider,
            DeadLetterRepository deadLetterRepository,
            ObjectMapper objectMapper) {

        @SuppressWarnings({"rawtypes", "unchecked"})
        KafkaTemplate<Object, Object> kafkaTemplate =
                (KafkaTemplate<Object, Object>) (KafkaTemplate) kafkaTemplateProvider.getIfAvailable();

        if (kafkaTemplate == null) {
            log.warn("[DLQ] Kafka unavailable — DLQ recovery disabled; failed messages will be logged only");
            return new DefaultErrorHandler(new FixedBackOff(1000L, 2L));
        }

        DeadLetterPublishingRecoverer dlqPublisher = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // DLQ topic has 1 partition; always route to partition 0
                (record, ex) -> new TopicPartition(transactionDlqTopic, 0));

        ConsumerRecordRecoverer recoverer =
                new PersistingDlqRecoverer(dlqPublisher, deadLetterRepository, objectMapper);

        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    /**
     * Wraps {@link DeadLetterPublishingRecoverer} to persist the failed record to
     * {@code dead_letter_events} before forwarding to the Kafka DLQ topic.
     *
     * <p>DB write is attempted first so that even a Kafka DLQ publish failure does not
     * result in a completely invisible failure. Any persistence error is logged but does
     * not prevent the Kafka publish from being attempted.
     */
    private static class PersistingDlqRecoverer implements ConsumerRecordRecoverer {

        private final DeadLetterPublishingRecoverer delegate;
        private final DeadLetterRepository deadLetterRepository;
        private final ObjectMapper objectMapper;

        PersistingDlqRecoverer(DeadLetterPublishingRecoverer delegate,
                               DeadLetterRepository deadLetterRepository,
                               ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.deadLetterRepository = deadLetterRepository;
            this.objectMapper = objectMapper;
        }

        @Override
        public void accept(ConsumerRecord<?, ?> record, Exception exception) {
            persistToDb(record, exception);
            try {
                delegate.accept(record, exception);
            } catch (Exception e) {
                // DB entry is already written — Kafka DLQ publish is best-effort.
                // Do NOT rethrow: Spring Kafka must see recovery as successful so it
                // commits the offset. If we propagate, the container retries forever.
                log.error("[DLQ] Kafka DLQ publish failed — record is in DB for admin retry: " +
                                "topic={}, partition={}, offset={}, error={}",
                        record.topic(), record.partition(), record.offset(), e.getMessage(), e);
            }
        }

        private void persistToDb(ConsumerRecord<?, ?> record, Exception exception) {
            try {
                String payload = serializeValue(record.value());
                // Unwrap Spring Kafka's ListenerExecutionFailedException to get the real error message
                String errorMessage = rootCauseMessage(exception);

                deadLetterRepository.save(new DeadLetterRepository.DeadLetterEntry(
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        payload,
                        errorMessage
                ));
            } catch (Exception e) {
                log.error("[DLQ] Failed to persist dead-letter entry to DB: " +
                                "topic={}, partition={}, offset={}, error={}",
                        record.topic(), record.partition(), record.offset(), e.getMessage(), e);
            }
        }

        private static String rootCauseMessage(Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return root.getMessage() != null ? root.getMessage() : root.getClass().getName();
        }

        private String serializeValue(Object value) {
            if (value == null) return "null";
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Bean
    public EventPublisher eventPublisher(ObjectProvider<KafkaTemplate> kafkaTemplateProvider) {
        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        if (kafkaTemplate != null) {
            log.info("Event publisher: Kafka (topic={})", transactionEventsTopic);
            return new KafkaEventPublisher(kafkaTemplate, transactionEventsTopic);
        }
        log.warn("Event publisher: no-op (Kafka unavailable — events will not be published)");
        return (tx, type) -> log.debug("Event skipped (no-op): transactionId={}, eventType={}",
                tx.getId(), type);
    }
}
