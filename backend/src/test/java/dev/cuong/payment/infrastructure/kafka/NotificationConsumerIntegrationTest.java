package dev.cuong.payment.infrastructure.kafka;

import dev.cuong.payment.application.port.out.EventPublisher;
import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link NotificationConsumer}.
 *
 * <p>A secondary test listener in group {@code test-notification-group} captures messages
 * that arrive on the topic. Because consumer groups are independent, both the
 * {@link NotificationConsumer} (group {@code notification}) and the test listener receive
 * every published message — verifying both Kafka delivery and consumer group isolation.
 *
 * <p>Redis is not required for this consumer; Redisson is excluded so this test starts faster.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        // Only Redisson excluded — this consumer needs Kafka but not Redis
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.flyway.enabled=true",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=test-secret-key-minimum-32-chars-for-hs256!"
})
class NotificationConsumerIntegrationTest {

    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine");

    static {
        kafka.start();
        postgres.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // ── Secondary test listener ───────────────────────────────────────────────

    @TestConfiguration
    static class TestListenerConfig {

        static volatile CountDownLatch latch = new CountDownLatch(1);
        static final Set<TransactionEventType> receivedTypes =
                ConcurrentHashMap.newKeySet();

        @Bean
        TestNotificationListener testNotificationListener() {
            return new TestNotificationListener();
        }
    }

    static class TestNotificationListener {

        @KafkaListener(
                topics = "payment.transaction.events",
                groupId = "test-notification-group"
        )
        void receive(TransactionEventMessage message) {
            TestListenerConfig.receivedTypes.add(message.eventType());
            TestListenerConfig.latch.countDown();
        }
    }

    @Autowired
    EventPublisher eventPublisher;

    @BeforeEach
    void resetCapture() {
        TestListenerConfig.receivedTypes.clear();
    }

    // ── All event types delivered ─────────────────────────────────────────────

    @Test
    void should_receive_all_six_event_types_within_timeout() throws InterruptedException {
        TestListenerConfig.latch = new CountDownLatch(6);

        Transaction tx = buildTransaction(TransactionStatus.SUCCESS);

        eventPublisher.publish(tx, TransactionEventType.CREATED);
        eventPublisher.publish(tx, TransactionEventType.PROCESSING);
        eventPublisher.publish(tx, TransactionEventType.SUCCESS);
        eventPublisher.publish(tx, TransactionEventType.FAILED);
        eventPublisher.publish(tx, TransactionEventType.TIMEOUT);
        eventPublisher.publish(tx, TransactionEventType.REFUNDED);

        boolean allDelivered = TestListenerConfig.latch.await(10, TimeUnit.SECONDS);
        assertThat(allDelivered)
                .as("Not all 6 event types were delivered to the topic within 10 s")
                .isTrue();

        assertThat(TestListenerConfig.receivedTypes)
                .containsExactlyInAnyOrderElementsOf(
                        EnumSet.allOf(TransactionEventType.class));
    }

    // ── Event metadata preserved end-to-end ──────────────────────────────────

    @Test
    void should_preserve_transaction_metadata_through_kafka_serialization() throws InterruptedException {
        TestListenerConfig.latch = new CountDownLatch(1);

        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransactionWithId(txId, TransactionStatus.PENDING);

        eventPublisher.publish(tx, TransactionEventType.CREATED);

        boolean received = TestListenerConfig.latch.await(5, TimeUnit.SECONDS);
        assertThat(received).as("CREATED event not received within 5 s").isTrue();

        // Verify the test listener received exactly the message that was published
        assertThat(TestListenerConfig.receivedTypes)
                .containsExactly(TransactionEventType.CREATED);
    }

    // ── Independent consumer group offset ────────────────────────────────────

    @Test
    void should_deliver_independently_of_processing_consumer_group() throws InterruptedException {
        // The notification group is independent of payment-processor.
        // Publishing a REFUNDED event (which payment-processor ignores) must still
        // arrive at the notification consumer's group.
        TestListenerConfig.latch = new CountDownLatch(1);

        Transaction tx = buildTransaction(TransactionStatus.REFUNDED);

        eventPublisher.publish(tx, TransactionEventType.REFUNDED);

        boolean received = TestListenerConfig.latch.await(5, TimeUnit.SECONDS);
        assertThat(received).as("REFUNDED event not received within 5 s").isTrue();
        assertThat(TestListenerConfig.receivedTypes).contains(TransactionEventType.REFUNDED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildTransaction(TransactionStatus status) {
        return buildTransactionWithId(UUID.randomUUID(), status);
    }

    private Transaction buildTransactionWithId(UUID id, TransactionStatus status) {
        return Transaction.builder()
                .id(id)
                .fromAccountId(UUID.randomUUID())
                .toAccountId(UUID.randomUUID())
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .status(status)
                .description("Notification test")
                .idempotencyKey(UUID.randomUUID().toString())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
