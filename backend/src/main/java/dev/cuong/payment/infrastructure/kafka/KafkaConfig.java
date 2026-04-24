package dev.cuong.payment.infrastructure.kafka;

import dev.cuong.payment.application.port.out.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

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
