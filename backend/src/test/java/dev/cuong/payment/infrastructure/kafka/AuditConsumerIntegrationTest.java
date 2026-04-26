package dev.cuong.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.EventPublisher;
import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.infrastructure.persistence.entity.AuditLogJpaEntity;
import dev.cuong.payment.infrastructure.persistence.repository.AuditLogJpaRepository;
import dev.cuong.payment.presentation.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * Integration test for {@link AuditConsumer}.
 *
 * <p>Uses real Postgres and Kafka. Redis is excluded — the audit consumer has no Redis
 * dependency. A registered user's account provides a valid {@code fromAccountId} so the
 * consumer can resolve {@code userId} from the DB without a fallback.
 *
 * <p>Tests are NOT {@code @Transactional}: the Kafka consumer runs in its own thread and
 * transaction. Awaitility polls the {@link AuditLogJpaRepository} until the expected
 * number of rows appear.
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
class AuditConsumerIntegrationTest {

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
    @Autowired AuditLogJpaRepository auditLogJpaRepository;

    // ── Core: every published event produces an audit entry ───────────────────

    @Test
    void should_insert_audit_entry_for_every_published_event() throws Exception {
        // Register a user so the consumer can resolve userId from fromAccountId
        String token     = registerAndGetToken("audit-alice", "audit-alice@test.com");
        UUID   userId    = extractUserId(token);
        UUID   fromAccId = accountRepository.findByUserId(userId).orElseThrow().getId();

        UUID txId = UUID.randomUUID();

        // Publish 3 events for this transaction (skip CREATED to avoid noisy retries
        // from TransactionProcessingConsumer which looks up the transaction row in DB)
        publishEvent(txId, fromAccId, TransactionStatus.PROCESSING, TransactionEventType.PROCESSING);
        publishEvent(txId, fromAccId, TransactionStatus.SUCCESS,    TransactionEventType.SUCCESS);
        publishEvent(txId, fromAccId, TransactionStatus.REFUNDED,   TransactionEventType.REFUNDED);

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(auditLogJpaRepository.countByTransactionId(txId)).isEqualTo(3));

        List<AuditLogJpaEntity> entries =
                auditLogJpaRepository.findByTransactionIdOrderByCreatedAtAsc(txId);

        assertThat(entries).extracting(AuditLogJpaEntity::getEventType)
                .containsExactlyInAnyOrder("PROCESSING", "SUCCESS", "REFUNDED");

        assertThat(entries).allSatisfy(e -> {
            assertThat(e.getTransactionId()).isEqualTo(txId);
            assertThat(e.getUserId()).isEqualTo(userId);          // resolved from account
            assertThat(e.getNewStatus()).isNotBlank();
            assertThat(e.getMetadata()).isNotBlank();
            assertThat(e.getCreatedAt()).isNotNull();
        });
    }

    // ── Metadata contains event fields ────────────────────────────────────────

    @Test
    void should_persist_metadata_with_amount_and_account_ids() throws Exception {
        String token     = registerAndGetToken("audit-bob", "audit-bob@test.com");
        UUID   userId    = extractUserId(token);
        UUID   fromAccId = accountRepository.findByUserId(userId).orElseThrow().getId();

        UUID txId      = UUID.randomUUID();
        UUID toAccId   = UUID.randomUUID();
        BigDecimal amt = new BigDecimal("750.00");

        Transaction tx = Transaction.builder()
                .id(txId)
                .fromAccountId(fromAccId)
                .toAccountId(toAccId)
                .amount(amt)
                .currency("USD")
                .status(TransactionStatus.SUCCESS)
                .description("Audit metadata test")
                .idempotencyKey(UUID.randomUUID().toString())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        eventPublisher.publish(tx, TransactionEventType.SUCCESS);

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(auditLogJpaRepository.countByTransactionId(txId)).isEqualTo(1));

        AuditLogJpaEntity entry =
                auditLogJpaRepository.findByTransactionIdOrderByCreatedAtAsc(txId).get(0);

        assertThat(entry.getEventType()).isEqualTo("SUCCESS");
        assertThat(entry.getNewStatus()).isEqualTo("SUCCESS");
        assertThat(entry.getMetadata()).contains("750");
        assertThat(entry.getMetadata()).contains(fromAccId.toString());
        assertThat(entry.getMetadata()).contains(toAccId.toString());
        assertThat(entry.getMetadata()).contains("USD");
    }

    // ── Each event type produces its own row (append-only) ───────────────────

    @Test
    void should_produce_separate_immutable_rows_for_each_event_not_update_existing() throws Exception {
        String token     = registerAndGetToken("audit-carol", "audit-carol@test.com");
        UUID   userId    = extractUserId(token);
        UUID   fromAccId = accountRepository.findByUserId(userId).orElseThrow().getId();

        UUID txId = UUID.randomUUID();

        publishEvent(txId, fromAccId, TransactionStatus.PROCESSING, TransactionEventType.PROCESSING);
        publishEvent(txId, fromAccId, TransactionStatus.SUCCESS,    TransactionEventType.SUCCESS);

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(auditLogJpaRepository.countByTransactionId(txId)).isEqualTo(2));

        // Both rows exist with distinct IDs — proof of append-only, no UPDATE
        List<AuditLogJpaEntity> entries =
                auditLogJpaRepository.findByTransactionIdOrderByCreatedAtAsc(txId);
        assertThat(entries).extracting(AuditLogJpaEntity::getId).doesNotHaveDuplicates();
        assertThat(entries).extracting(AuditLogJpaEntity::getEventType)
                .containsExactly("PROCESSING", "SUCCESS");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void publishEvent(UUID txId, UUID fromAccId,
                              TransactionStatus status, TransactionEventType type) {
        Transaction tx = Transaction.builder()
                .id(txId)
                .fromAccountId(fromAccId)
                .toAccountId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .status(status)
                .description("Audit test")
                .idempotencyKey(UUID.randomUUID().toString())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        eventPublisher.publish(tx, type);
    }

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
