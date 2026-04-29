package dev.cuong.payment.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.presentation.auth.LoginRequest;
import dev.cuong.payment.presentation.auth.RegisterRequest;
import dev.cuong.payment.presentation.transaction.CreateTransactionRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests covering the full transaction lifecycle through
 * the real HTTP stack — Spring Security filters, controllers, services, JPA,
 * Kafka producer/consumer, Redisson lock, MockPaymentGateway — with all
 * infrastructure provisioned via Testcontainers (Postgres + Kafka + Redis).
 *
 * <p><strong>Determinism:</strong> overrides {@code app.gateway.success-rate=1.0}
 * so the simulated payment gateway never produces failures or timeouts. Other
 * tests cover failure-path behaviour. Rate limiter is widened to 1000/min so
 * the concurrent test is not throttled.
 *
 * <p><strong>Tests are not {@code @Transactional}:</strong> the Kafka consumer
 * commits in its own transaction; an outer rollback-only test transaction would
 * hide its writes. Each test uses unique usernames so reruns inside the same
 * JVM (with shared static containers) don't collide.
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
        "app.rate-limit.max-requests-per-minute=1000",
        // Real MockPaymentGateway, but pinned to 100% success — failure paths are tested elsewhere.
        "app.gateway.success-rate=1.0",
        "app.gateway.timeout-rate=0.0",
        "app.gateway.fail-rate=0.0",
        // Keep circuit breaker effectively inactive within the test window.
        "resilience4j.circuitbreaker.instances.payment-gateway.sliding-window-size=1000",
        "resilience4j.circuitbreaker.instances.payment-gateway.minimum-number-of-calls=1000"
})
class EndToEndIntegrationTest {

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

    @TestConfiguration
    static class TestRedissonConfig {
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
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;

    // ── Test 1: happy path — register × 2 → seed → transfer → SUCCESS → refund ──

    @Test
    void should_complete_full_lifecycle_register_seed_transfer_refund() throws Exception {
        String senderUsername = "alice-" + UUID.randomUUID().toString().substring(0, 8);
        String receiverUsername = "bob-" + UUID.randomUUID().toString().substring(0, 8);

        String senderToken = registerAndGetToken(senderUsername, senderUsername + "@test.com");
        String receiverToken = registerAndGetToken(receiverUsername, receiverUsername + "@test.com");

        UUID senderAccountId = accountIdFor(senderToken);
        UUID receiverAccountId = accountIdFor(receiverToken);

        // Seed the sender's account with starting balance — there is no deposit
        // endpoint; production accounts would be funded via an external rail.
        creditAccount(senderAccountId, new BigDecimal("1000.00"));

        // Transfer 250 from sender to receiver.
        BigDecimal transferAmount = new BigDecimal("250.00");
        UUID transactionId = createTransfer(senderToken, receiverAccountId, transferAmount);

        // Sender debit is synchronous within the POST.
        assertThat(balanceOf(senderAccountId)).isEqualByComparingTo("750.00");

        // Wait for the async consumer to pay the gateway and credit the receiver.
        await().atMost(15, SECONDS).untilAsserted(() -> {
            assertTransactionStatus(senderToken, transactionId, "SUCCESS");
            assertThat(balanceOf(receiverAccountId)).isEqualByComparingTo("250.00");
        });

        // Refund — synchronous credit back to sender.
        refund(senderToken, transactionId);
        assertTransactionStatus(senderToken, transactionId, "REFUNDED");
        assertThat(balanceOf(senderAccountId)).isEqualByComparingTo("1000.00");
    }

    // ── Test 2: 10 concurrent transfers from one account, finite balance ──────

    /**
     * 10 threads each attempt to transfer 200 from a sender that holds 1000.
     * Demand (2000) exceeds supply (1000); the application service's
     * pessimistic lock + domain-level {@code debit()} check must serialise the
     * threads so exactly five succeed and five fail, with the sender's final
     * balance landing at exactly zero — no double-debit, no lost debit.
     */
    @Test
    void should_serialize_concurrent_transfers_and_prevent_overdraft() throws Exception {
        String senderUsername = "carol-" + UUID.randomUUID().toString().substring(0, 8);
        String receiverUsername = "dave-" + UUID.randomUUID().toString().substring(0, 8);

        String senderToken = registerAndGetToken(senderUsername, senderUsername + "@test.com");
        String receiverToken = registerAndGetToken(receiverUsername, receiverUsername + "@test.com");
        UUID senderAccountId = accountIdFor(senderToken);
        UUID receiverAccountId = accountIdFor(receiverToken);

        creditAccount(senderAccountId, new BigDecimal("1000.00"));

        int threads = 10;
        BigDecimal amountEach = new BigDecimal("200.00");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> attemptTransferStatus(senderToken, receiverAccountId, amountEach));
            }
            List<Future<Integer>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            int successes = 0;
            int rejections = 0;
            for (Future<Integer> f : results) {
                int statusCode = f.get();
                if (statusCode == 201) successes++;
                else if (statusCode == 422) rejections++;
                else throw new AssertionError("unexpected status: " + statusCode);
            }

            assertThat(successes).as("exactly 5 transfers should fit in the 1000 balance")
                    .isEqualTo(5);
            assertThat(rejections).as("the other 5 should fail with 422 InsufficientFunds")
                    .isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }

        // Sender debit is committed synchronously per successful transfer — final
        // balance reflects exactly five debits regardless of any in-flight async
        // gateway processing.
        assertThat(balanceOf(senderAccountId))
                .as("final sender balance — exactly zero, no double-debit, no lost debit")
                .isEqualByComparingTo("0.00");

        // Wait for all five async credits to land on the receiver.
        await().atMost(20, SECONDS).untilAsserted(() ->
                assertThat(balanceOf(receiverAccountId)).isEqualByComparingTo("1000.00"));
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String registerAndGetToken(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, email, "password123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    @SuppressWarnings("unused")
    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    private UUID accountIdFor(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    /**
     * Seeds funds onto an account by loading the aggregate, calling its
     * {@code credit()} domain method, and saving. There is no deposit endpoint;
     * tests must seed directly.
     */
    private void creditAccount(UUID accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.credit(amount);
        accountRepository.save(account);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    private UUID createTransfer(String token, UUID toAccountId, BigDecimal amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateTransactionRequest(toAccountId, amount, "E2E test"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    /** Performs a transfer and returns just the HTTP status code — for the concurrency test. */
    private int attemptTransferStatus(String token, UUID toAccountId, BigDecimal amount) throws Exception {
        return mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateTransactionRequest(toAccountId, amount, "concurrency"))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void refund(String token, UUID transactionId) throws Exception {
        mockMvc.perform(post("/api/transactions/" + transactionId + "/refund")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void assertTransactionStatus(String token, UUID transactionId, String expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("status").asText()).isEqualTo(expectedStatus);
    }
}
