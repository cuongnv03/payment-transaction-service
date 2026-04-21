package dev.cuong.payment.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.presentation.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@TestPropertySource(properties = {
        // Exclude only Kafka — Redisson is excluded too and replaced by @TestConfiguration below
        "spring.autoconfigure.exclude=" +
                "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "app.jwt.secret=test-secret-key-minimum-32-chars-for-hs256!",
        // Lower limit to 3 so the test completes quickly without sending 10 real requests
        "app.rate-limit.max-requests-per-minute=3",
        "app.rate-limit.window-seconds=60"
})
class RateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
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

    // ── Happy path: under the limit ───────────────────────────────────────────

    @Test
    void should_allow_requests_within_rate_limit() throws Exception {
        String senderToken   = registerAndGetToken("alice", "alice@test.com");
        String receiverToken = registerAndGetToken("bob",   "bob@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("5000.00"));

        // 3 requests = exactly at the limit configured in test properties (limit=3)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/transactions")
                            .header("Authorization", "Bearer " + senderToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(txBody(toAccountId, "10.00")))
                    .andExpect(status().isCreated());
        }
    }

    // ── Rate limit exceeded ───────────────────────────────────────────────────

    @Test
    void should_return_429_when_rate_limit_exceeded() throws Exception {
        String senderToken   = registerAndGetToken("carol", "carol@test.com");
        String receiverToken = registerAndGetToken("dave",  "dave@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("5000.00"));

        // Send limit (3) requests — all should succeed
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/transactions")
                            .header("Authorization", "Bearer " + senderToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(txBody(toAccountId, "10.00")))
                    .andExpect(status().isCreated());
        }

        // The 4th request (limit + 1) must be rejected
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "10.00")))
                .andExpect(status().is(429))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    // ── User isolation ────────────────────────────────────────────────────────

    @Test
    void should_not_affect_other_users_when_one_user_is_rate_limited() throws Exception {
        String aliceToken    = registerAndGetToken("alice2", "alice2@test.com");
        String bobToken      = registerAndGetToken("bob2",   "bob2@test.com");
        String carolToken    = registerAndGetToken("carol2", "carol2@test.com");
        UUID aliceUserId     = extractUserId(aliceToken);
        UUID bobUserId       = extractUserId(bobToken);
        UUID carolToAccountId = accountRepository.findByUserId(extractUserId(carolToken)).orElseThrow().getId();

        fundAccount(aliceUserId, new BigDecimal("5000.00"));
        fundAccount(bobUserId, new BigDecimal("5000.00"));

        // Exhaust Alice's rate limit
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/transactions")
                            .header("Authorization", "Bearer " + aliceToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(txBody(carolToAccountId, "10.00")))
                    .andExpect(status().isCreated());
        }

        // Alice is now rate limited
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(carolToAccountId, "10.00")))
                .andExpect(status().is(429));

        // Bob's requests are unaffected — his bucket is independent
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + bobToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(carolToAccountId, "10.00")))
                .andExpect(status().isCreated());
    }

    // ── Rate limiting only on transaction endpoint ────────────────────────────

    @Test
    void should_not_rate_limit_non_transaction_endpoints() throws Exception {
        String senderToken   = registerAndGetToken("eve",  "eve@test.com");
        String receiverToken = registerAndGetToken("fred", "fred@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("5000.00"));

        // Exhaust rate limit
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/transactions")
                            .header("Authorization", "Bearer " + senderToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(txBody(toAccountId, "10.00")))
                    .andExpect(status().isCreated());
        }

        // Rate limited on POST /api/transactions
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "10.00")))
                .andExpect(status().is(429));

        // GET /api/transactions is NOT rate limited — should still work
        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk());
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

    private String txBody(UUID toAccountId, String amount) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("toAccountId", toAccountId.toString());
        node.put("amount", new BigDecimal(amount));
        return objectMapper.writeValueAsString(node);
    }
}
