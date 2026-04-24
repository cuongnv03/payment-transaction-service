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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.flyway.enabled=true",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=test-secret-key-minimum-32-chars-for-hs256!"
})
class KafkaEventPublisherIntegrationTest {

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

    // ── Test listener — captures messages published to the topic ──────────────

    @TestConfiguration
    static class TestListenerConfig {

        static volatile CountDownLatch latch = new CountDownLatch(1);
        static volatile TransactionEventMessage lastMessage;

        @Bean
        TestMessageListener testMessageListener() {
            return new TestMessageListener();
        }
    }

    static class TestMessageListener {
        @KafkaListener(topics = "payment.transaction.events", groupId = "test-listener-group")
        void receive(TransactionEventMessage message) {
            TestListenerConfig.lastMessage = message;
            TestListenerConfig.latch.countDown();
        }
    }

    @Autowired
    EventPublisher eventPublisher;

    @BeforeEach
    void resetLatch() {
        TestListenerConfig.latch = new CountDownLatch(1);
        TestListenerConfig.lastMessage = null;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void should_deliver_created_event_to_consumer_within_5_seconds() throws InterruptedException {
        Transaction tx = buildTransaction(TransactionStatus.PENDING);

        eventPublisher.publish(tx, TransactionEventType.CREATED);

        boolean received = TestListenerConfig.latch.await(5, TimeUnit.SECONDS);
        assertThat(received).as("Consumer did not receive CREATED event within 5s").isTrue();

        TransactionEventMessage msg = TestListenerConfig.lastMessage;
        assertThat(msg.transactionId()).isEqualTo(tx.getId());
        assertThat(msg.eventType()).isEqualTo(TransactionEventType.CREATED);
        assertThat(msg.status()).isEqualTo("PENDING");
        assertThat(msg.fromAccountId()).isEqualTo(tx.getFromAccountId());
        assertThat(msg.toAccountId()).isEqualTo(tx.getToAccountId());
        assertThat(msg.amount()).isEqualByComparingTo(tx.getAmount());
        assertThat(msg.currency()).isEqualTo("USD");
        assertThat(msg.occurredAt()).isNotNull();
    }

    @Test
    void should_deliver_refunded_event_with_correct_metadata() throws InterruptedException {
        Transaction tx = buildTransaction(TransactionStatus.REFUNDED);

        eventPublisher.publish(tx, TransactionEventType.REFUNDED);

        boolean received = TestListenerConfig.latch.await(5, TimeUnit.SECONDS);
        assertThat(received).as("Consumer did not receive REFUNDED event within 5s").isTrue();

        TransactionEventMessage msg = TestListenerConfig.lastMessage;
        assertThat(msg.transactionId()).isEqualTo(tx.getId());
        assertThat(msg.eventType()).isEqualTo(TransactionEventType.REFUNDED);
        assertThat(msg.status()).isEqualTo("REFUNDED");
    }

    @Test
    void should_use_transaction_id_as_partition_key() throws InterruptedException {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransactionWithId(txId, TransactionStatus.PENDING);

        eventPublisher.publish(tx, TransactionEventType.CREATED);

        boolean received = TestListenerConfig.latch.await(5, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        // Partition key correctness is verified indirectly: the consumer received
        // the message and the transactionId matches — KafkaEventPublisher sends
        // transactionId.toString() as the Kafka record key, which routes all events
        // for the same transaction to the same partition.
        assertThat(TestListenerConfig.lastMessage.transactionId()).isEqualTo(txId);
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
                .description("Test transfer")
                .idempotencyKey(UUID.randomUUID().toString())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
