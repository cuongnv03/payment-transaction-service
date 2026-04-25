package dev.cuong.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.EventPublisher;
import dev.cuong.payment.application.port.out.PaymentGatewayPort;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.domain.exception.PaymentGatewayException;
import dev.cuong.payment.domain.exception.PaymentGatewayTimeoutException;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.presentation.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@link TransactionProcessingConsumer}.
 *
 * <p>Uses real Postgres, Kafka, and Redis containers. {@link PaymentGatewayPort} is replaced
 * by a stub controlled via {@link #gatewayMode} so each test scenario is deterministic.
 *
 * <p>Tests are NOT {@code @Transactional}: the Kafka consumer runs in its own thread and
 * transaction; an outer rollback-only test transaction would hide committed data from it.
 * Each test uses unique usernames to avoid state conflicts within the shared static containers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Exclude Redisson auto-config — replaced by TestRedissonConfig below
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "spring.flyway.enabled=true",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=test-secret-key-minimum-32-chars-for-hs256!",
        // High limit so tests don't hit the rate limiter
        "app.rate-limit.max-requests-per-minute=1000",
        // Keep circuit breaker inactive — stub gateway is deterministic, no need for resilience4j state
        "resilience4j.circuitbreaker.instances.payment-gateway.sliding-window-size=1000",
        "resilience4j.circuitbreaker.instances.payment-gateway.minimum-number-of-calls=1000"
})
class TransactionProcessingConsumerIntegrationTest {

    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        kafka.start();
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ── Gateway stub + Redis wiring ───────────────────────────────────────────

    enum GatewayMode { SUCCESS, FAIL, TIMEOUT }

    static final AtomicReference<GatewayMode> gatewayMode =
            new AtomicReference<>(GatewayMode.SUCCESS);

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        RedissonClient testRedissonClient() {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
                    .setConnectionPoolSize(4)
                    .setConnectionMinimumIdleSize(1);
            return Redisson.create(config);
        }

        // Overrides MockPaymentGateway — gives tests full control over gateway outcomes
        // without the non-determinism of random rolls or the delay of Resilience4j retries.
        @Bean
        @Primary
        PaymentGatewayPort stubPaymentGateway() {
            return (txId, amount) -> switch (gatewayMode.get()) {
                case SUCCESS -> "GW-STUB-" + txId.toString().replace("-", "").substring(0, 8).toUpperCase();
                case FAIL -> throw new PaymentGatewayException("Stubbed rejection");
                case TIMEOUT -> throw new PaymentGatewayTimeoutException("Stubbed timeout");
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired EventPublisher eventPublisher;

    @BeforeEach
    void resetGateway() {
        gatewayMode.set(GatewayMode.SUCCESS);
    }

    // ── SUCCESS: gateway accepts ──────────────────────────────────────────────

    @Test
    void should_credit_receiver_and_mark_success_when_gateway_accepts() throws Exception {
        String senderToken   = registerAndGetToken("cpc-alice", "cpc-alice@test.com");
        String receiverToken = registerAndGetToken("cpc-bob",   "cpc-bob@test.com");

        UUID senderUserId   = extractUserId(senderToken);
        UUID receiverUserId = extractUserId(receiverToken);
        UUID toAccountId    = accountRepository.findByUserId(receiverUserId).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));

        UUID txId = createTransaction(senderToken, toAccountId, "200.00", "Success test");

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.SUCCESS));

        Transaction tx = transactionRepository.findById(txId).orElseThrow();
        assertThat(tx.getGatewayReference()).isNotBlank();

        // Receiver credited on SUCCESS
        Account receiver = accountRepository.findByUserId(receiverUserId).orElseThrow();
        assertThat(receiver.getBalance()).isEqualByComparingTo("200.00");

        // Sender: 500 initial − 200 debit on CREATED = 300; SUCCESS does NOT restore sender
        Account sender = accountRepository.findByUserId(senderUserId).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo("300.00");
    }

    // ── FAILED: gateway permanently rejects ──────────────────────────────────

    @Test
    void should_restore_sender_and_mark_failed_when_gateway_rejects() throws Exception {
        gatewayMode.set(GatewayMode.FAIL);

        String senderToken   = registerAndGetToken("cpc-carol", "cpc-carol@test.com");
        String receiverToken = registerAndGetToken("cpc-dave",  "cpc-dave@test.com");

        UUID senderUserId   = extractUserId(senderToken);
        UUID receiverUserId = extractUserId(receiverToken);
        UUID toAccountId    = accountRepository.findByUserId(receiverUserId).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));

        UUID txId = createTransaction(senderToken, toAccountId, "200.00", "Fail test");

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.FAILED));

        Transaction tx = transactionRepository.findById(txId).orElseThrow();
        assertThat(tx.getFailureReason()).isNotBlank();

        // Sender restored: 500 − 200 (debit on CREATED) + 200 (restore on FAILED) = 500
        Account sender = accountRepository.findByUserId(senderUserId).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo("500.00");

        // Receiver never credited
        Account receiver = accountRepository.findByUserId(receiverUserId).orElseThrow();
        assertThat(receiver.getBalance()).isEqualByComparingTo("0.00");
    }

    // ── TIMEOUT: gateway times out ────────────────────────────────────────────

    @Test
    void should_restore_sender_and_mark_timeout_when_gateway_times_out() throws Exception {
        gatewayMode.set(GatewayMode.TIMEOUT);

        String senderToken   = registerAndGetToken("cpc-eve",  "cpc-eve@test.com");
        String receiverToken = registerAndGetToken("cpc-frank", "cpc-frank@test.com");

        UUID senderUserId   = extractUserId(senderToken);
        UUID receiverUserId = extractUserId(receiverToken);
        UUID toAccountId    = accountRepository.findByUserId(receiverUserId).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("300.00"));

        UUID txId = createTransaction(senderToken, toAccountId, "150.00", "Timeout test");

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.TIMEOUT));

        // Sender restored: 300 − 150 + 150 = 300
        Account sender = accountRepository.findByUserId(senderUserId).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo("300.00");

        Account receiver = accountRepository.findByUserId(receiverUserId).orElseThrow();
        assertThat(receiver.getBalance()).isEqualByComparingTo("0.00");
    }

    // ── Idempotency: duplicate CREATED event is a no-op ──────────────────────

    @Test
    void should_not_reprocess_when_duplicate_created_event_arrives_after_success() throws Exception {
        String senderToken   = registerAndGetToken("cpc-george", "cpc-george@test.com");
        String receiverToken = registerAndGetToken("cpc-helen",  "cpc-helen@test.com");

        UUID senderUserId   = extractUserId(senderToken);
        UUID receiverUserId = extractUserId(receiverToken);
        UUID toAccountId    = accountRepository.findByUserId(receiverUserId).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));

        // Initial creation → CREATED event published → consumer processes → SUCCESS
        UUID txId = createTransaction(senderToken, toAccountId, "100.00", "Idempotency test");

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.SUCCESS));

        // Receiver credited exactly once so far
        assertThat(accountRepository.findByUserId(receiverUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");

        // Simulate Kafka re-delivery: publish a duplicate CREATED for the already-SUCCESS transaction.
        // Switch to FAIL mode — if the consumer naively re-processes, it would mark the tx FAILED.
        gatewayMode.set(GatewayMode.FAIL);
        Transaction successTx = transactionRepository.findById(txId).orElseThrow();
        eventPublisher.publish(successTx, TransactionEventType.CREATED);

        // Give the consumer enough time to pick up and (incorrectly) reprocess — if it did,
        // status would change to FAILED within a few seconds.
        Thread.sleep(3000);

        // Status must still be SUCCESS — the consumer's Phase 1 detects non-PENDING and skips
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.SUCCESS);

        // Receiver balance unchanged — no double-credit
        assertThat(accountRepository.findByUserId(receiverUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
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

    private void fundAccount(UUID userId, BigDecimal amount) {
        Account account = accountRepository.findByUserId(userId).orElseThrow();
        account.credit(amount);
        accountRepository.save(account);
    }

    private UUID createTransaction(String token, UUID toAccountId, String amount, String description)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, amount, description)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String txBody(UUID toAccountId, String amount, String description) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("toAccountId", toAccountId.toString());
        node.put("amount", new BigDecimal(amount));
        if (description != null) node.put("description", description);
        return objectMapper.writeValueAsString(node);
    }
}
