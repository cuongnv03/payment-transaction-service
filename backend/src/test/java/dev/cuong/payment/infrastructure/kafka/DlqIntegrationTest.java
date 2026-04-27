package dev.cuong.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.EventPublisher;
import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.infrastructure.persistence.entity.DeadLetterEventJpaEntity;
import dev.cuong.payment.infrastructure.persistence.repository.DeadLetterEventJpaRepository;
import dev.cuong.payment.presentation.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test for the DLQ path: consumer failure → retry exhaustion → DB persistence.
 *
 * <p>Uses real Postgres and Kafka. Redis is excluded — the DLQ path has no Redis dependency.
 *
 * <p>The inner {@code AlwaysFailConsumerConfig} registers a listener on the main topic with
 * a dedicated group ("test-always-fail-group") that unconditionally throws. Spring Kafka's
 * {@code kafkaErrorHandler} retries twice (FixedBackOff 1 s × 2), then calls
 * the {@code PersistingDlqRecoverer} which writes the failure to {@code dead_letter_events}.
 *
 * <p>Tests are NOT {@code @Transactional}: the consumer and error handler run in separate
 * threads with their own transactions. Awaitility polls until the expected DB row appears.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.flyway.enabled=true",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=test-secret-key-minimum-32-chars-for-hs256!",
        "app.rate-limit.max-requests-per-minute=1000"
})
class DlqIntegrationTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventPublisher eventPublisher;
    @Autowired AccountRepository accountRepository;
    @Autowired DeadLetterEventJpaRepository deadLetterEventJpaRepository;

    // ── Core: exhausted retries produce a dead_letter_events row ─────────────

    @Test
    void should_persist_dlq_entry_when_consumer_fails_after_all_retries() throws Exception {
        // Register a user so the AuditConsumer can resolve userId from fromAccountId
        String token     = registerAndGetToken("dlq-alice", "dlq-alice@test.com");
        UUID   userId    = extractUserId(token);
        UUID   fromAccId = accountRepository.findByUserId(userId).orElseThrow().getId();

        UUID txId    = UUID.randomUUID();
        UUID toAccId = UUID.randomUUID();

        // Publish a PROCESSING event (not CREATED, to avoid TransactionProcessingConsumer
        // looking up a non-existent transaction row and creating unrelated DLQ entries)
        Transaction tx = Transaction.builder()
                .id(txId)
                .fromAccountId(fromAccId)
                .toAccountId(toAccId)
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .status(TransactionStatus.PROCESSING)
                .description("DLQ integration test")
                .idempotencyKey(UUID.randomUUID().toString())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        eventPublisher.publish(tx, TransactionEventType.PROCESSING);

        // FixedBackOff(1000L, 2L) = 2 retries, ~3 s total before recoverer is called.
        // Allow 20 s for the full retry cycle + DB write.
        await().atMost(20, SECONDS).untilAsserted(() -> {
            List<DeadLetterEventJpaEntity> dlqEntries = deadLetterEventJpaRepository.findAll()
                    .stream()
                    .filter(e -> "SIMULATED_FAILURE_FOR_DLQ_TEST".equals(e.getErrorMessage()))
                    .toList();
            assertThat(dlqEntries).isNotEmpty();
        });

        // Verify the persisted entry contains the expected fields
        DeadLetterEventJpaEntity dlqEntry = deadLetterEventJpaRepository.findAll()
                .stream()
                .filter(e -> "SIMULATED_FAILURE_FOR_DLQ_TEST".equals(e.getErrorMessage()))
                .findFirst()
                .orElseThrow();

        assertThat(dlqEntry.getTopic()).isNotBlank();
        assertThat(dlqEntry.getPayload()).contains(txId.toString());
        assertThat(dlqEntry.getPayload()).contains("PROCESSING");
        assertThat(dlqEntry.getCreatedAt()).isNotNull();
        assertThat(dlqEntry.getResolvedAt()).isNull();
    }

    // ── Inner test configuration: consumer that always throws ─────────────────

    /**
     * Registers an additional Kafka listener in group {@code test-always-fail-group}.
     * Every received message triggers the global {@code kafkaErrorHandler}, which retries
     * twice then routes the record to the {@code PersistingDlqRecoverer} in {@link KafkaConfig}.
     */
    @TestConfiguration
    static class AlwaysFailConsumerConfig {

        @KafkaListener(
                topics = "${app.kafka.topics.transaction-events}",
                groupId = "test-always-fail-group"
        )
        public void failingConsumer(TransactionEventMessage event) {
            throw new RuntimeException("SIMULATED_FAILURE_FOR_DLQ_TEST");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String registerAndGetToken(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, email, "password123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID extractUserId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
