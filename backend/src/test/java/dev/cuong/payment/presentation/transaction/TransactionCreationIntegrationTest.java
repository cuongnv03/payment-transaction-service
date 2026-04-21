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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void should_create_transaction_and_debit_sender_when_funds_are_sufficient() throws Exception {
        String senderToken   = registerAndGetToken("alice", "alice@test.com", "password123");
        String receiverToken = registerAndGetToken("bob",   "bob@test.com",   "password123");

        UUID senderUserId   = extractUserId(senderToken);
        UUID receiverUserId = extractUserId(receiverToken);
        UUID toAccountId    = accountRepository.findByUserId(receiverUserId).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "250.00", "Test payment")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.fromAccountId").isNotEmpty())
                .andExpect(jsonPath("$.toAccountId").value(toAccountId.toString()));

        Account sender = accountRepository.findByUserId(senderUserId).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo("750.00");

        // Receiver balance unchanged until consumer credits on SUCCESS (Task 12)
        Account receiver = accountRepository.findByUserId(receiverUserId).orElseThrow();
        assertThat(receiver.getBalance()).isEqualByComparingTo("0.00");
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    void should_return_cached_result_when_idempotency_key_is_reused() throws Exception {
        String senderToken   = registerAndGetToken("carol", "carol@test.com", "password123");
        String receiverToken = registerAndGetToken("dave",  "dave@test.com",  "password123");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("1000.00"));

        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "100.00", "First attempt")))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "100.00", "Retry")))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId  = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertThat(firstId).isEqualTo(secondId);

        // Balance deducted only once
        assertThat(accountRepository.findByUserId(senderUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("900.00");
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void should_reject_when_sender_has_insufficient_funds() throws Exception {
        String senderToken   = registerAndGetToken("eve",  "eve@test.com",  "password123");
        String receiverToken = registerAndGetToken("fred", "fred@test.com", "password123");
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();
        // Sender has 0 balance — any payment must fail

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "100.00", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void should_reject_transfer_to_own_account() throws Exception {
        String token  = registerAndGetToken("grace", "grace@test.com", "password123");
        UUID userId   = extractUserId(token);
        UUID ownAccId = accountRepository.findByUserId(userId).orElseThrow().getId();
        fundAccount(userId, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(ownAccId, "100.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @Test
    void should_reject_when_idempotency_key_header_is_missing() throws Exception {
        String token = registerAndGetToken("henry", "henry@test.com", "password123");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(UUID.randomUUID(), "50.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void should_reject_unauthenticated_request() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(UUID.randomUUID(), "50.00", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_reject_when_amount_is_negative() throws Exception {
        String token = registerAndGetToken("ivy", "ivy@test.com", "password123");

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(UUID.randomUUID(), "-50.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void should_reject_when_to_account_id_is_missing() throws Exception {
        String token = registerAndGetToken("jack", "jack@test.com", "password123");

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

    private String txBody(UUID toAccountId, String amount, String description) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("toAccountId", toAccountId.toString());
        node.put("amount", new java.math.BigDecimal(amount));
        if (description != null) node.put("description", description);
        return objectMapper.writeValueAsString(node);
    }
}
