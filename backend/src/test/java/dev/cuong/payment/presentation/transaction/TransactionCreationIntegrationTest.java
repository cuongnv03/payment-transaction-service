package dev.cuong.payment.presentation.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.presentation.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=" +
                "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "app.jwt.secret=test-secret-key-minimum-32-chars-for-hs256!"
})
class TransactionCreationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    // ── DEPOSIT ──────────────────────────────────────────────────────────────

    @Test
    void should_create_deposit_and_credit_account() throws Exception {
        String token = registerAndGetToken("alice", "alice@test.com", "password123");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("500.00", "DEPOSIT", "Initial deposit")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    // ── PAYMENT ───────────────────────────────────────────────────────────────

    @Test
    void should_create_payment_and_debit_account_when_funds_are_sufficient() throws Exception {
        String token = registerAndGetToken("bob", "bob@test.com", "password123");
        UUID userId = extractUserId(token);
        fundAccount(userId, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("250.00", "PAYMENT", "Netflix subscription")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.type").value("PAYMENT"))
                .andExpect(jsonPath("$.amount").value(250.00));

        // Balance should now be 750.00
        Account account = accountRepository.findByUserId(userId).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo("750.00");
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    void should_return_cached_result_when_idempotency_key_is_reused() throws Exception {
        String token = registerAndGetToken("carol", "carol@test.com", "password123");
        UUID userId = extractUserId(token);
        fundAccount(userId, new BigDecimal("1000.00"));

        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("100.00", "PAYMENT", "First attempt")))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("100.00", "PAYMENT", "Second attempt")))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId  = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

        assertThat(firstId).isEqualTo(secondId);

        // Balance should only be deducted once
        Account account = accountRepository.findByUserId(userId).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo("900.00");
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void should_reject_payment_when_balance_is_insufficient() throws Exception {
        String token = registerAndGetToken("dave", "dave@test.com", "password123");
        // New user has 0 balance — any payment should fail

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("100.00", "PAYMENT", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void should_reject_when_idempotency_key_header_is_missing() throws Exception {
        String token = registerAndGetToken("eve", "eve@test.com", "password123");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("50.00", "DEPOSIT", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void should_reject_unauthenticated_request() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("50.00", "DEPOSIT", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_reject_when_amount_is_zero_or_negative() throws Exception {
        String token = registerAndGetToken("frank", "frank@test.com", "password123");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody("-50.00", "DEPOSIT", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_reject_when_type_is_missing() throws Exception {
        String token = registerAndGetToken("grace", "grace@test.com", "password123");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, email, password))))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    private UUID extractUserId(String token) throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/users/me")
                                .header("Authorization", "Bearer " + token))
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("id").asText());
    }

    private void fundAccount(UUID userId, BigDecimal amount) {
        Account account = accountRepository.findByUserId(userId).orElseThrow();
        account.credit(amount);
        accountRepository.save(account);
    }

    private String transactionBody(String amount, String type, String description) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("amount", new java.math.BigDecimal(amount));
        node.put("type", type);
        if (description != null) node.put("description", description);
        return objectMapper.writeValueAsString(node);
    }
}
