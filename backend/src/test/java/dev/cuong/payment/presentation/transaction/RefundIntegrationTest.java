package dev.cuong.payment.presentation.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
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
class RefundIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void should_refund_transaction_and_restore_sender_balance() throws Exception {
        String senderToken   = registerAndGetToken("alice", "alice@test.com");
        String receiverToken = registerAndGetToken("bob",   "bob@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));

        String txId = createTransaction(senderToken, toAccountId, "200.00");

        // Advance transaction to SUCCESS (simulates Task 12 consumer — not yet implemented)
        advanceToSuccess(UUID.fromString(txId));

        // Sender balance after debit: 300.00
        assertThat(accountRepository.findByUserId(senderUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("300.00");

        mockMvc.perform(post("/api/transactions/" + txId + "/refund")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAt").isNotEmpty());

        // Balance fully restored after refund
        assertThat(accountRepository.findByUserId(senderUserId).orElseThrow().getBalance())
                .isEqualByComparingTo("500.00");
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void should_reject_refund_when_transaction_is_pending() throws Exception {
        String senderToken   = registerAndGetToken("carol", "carol@test.com");
        String receiverToken = registerAndGetToken("dave",  "dave@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));
        String txId = createTransaction(senderToken, toAccountId, "100.00");

        // Transaction is PENDING — state machine rejects PENDING → REFUNDED
        mockMvc.perform(post("/api/transactions/" + txId + "/refund")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSACTION_STATE"));
    }

    @Test
    void should_reject_double_refund() throws Exception {
        String senderToken   = registerAndGetToken("eve",  "eve@test.com");
        String receiverToken = registerAndGetToken("fred", "fred@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));
        String txId = createTransaction(senderToken, toAccountId, "100.00");
        advanceToSuccess(UUID.fromString(txId));

        // First refund — succeeds
        mockMvc.perform(post("/api/transactions/" + txId + "/refund")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        // Second refund on the same transaction — state machine rejects REFUNDED → REFUNDED
        mockMvc.perform(post("/api/transactions/" + txId + "/refund")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSACTION_STATE"));
    }

    @Test
    void should_return_404_when_refunding_another_users_transaction() throws Exception {
        String aliceToken    = registerAndGetToken("alice2", "alice2@test.com");
        String bobToken      = registerAndGetToken("bob2",   "bob2@test.com");
        UUID aliceUserId     = extractUserId(aliceToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(bobToken)).orElseThrow().getId();

        fundAccount(aliceUserId, new BigDecimal("500.00"));
        String txId = createTransaction(aliceToken, toAccountId, "100.00");
        advanceToSuccess(UUID.fromString(txId));

        // Bob tries to refund Alice's transaction — must get 404, not 403
        mockMvc.perform(post("/api/transactions/" + txId + "/refund")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_404_when_transaction_does_not_exist() throws Exception {
        String token = registerAndGetToken("grace", "grace@test.com");

        mockMvc.perform(post("/api/transactions/" + UUID.randomUUID() + "/refund")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_401_when_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/transactions/" + UUID.randomUUID() + "/refund"))
                .andExpect(status().isUnauthorized());
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

    private String createTransaction(String senderToken, UUID toAccountId, String amount) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("toAccountId", toAccountId.toString());
        node.put("amount", new BigDecimal(amount));

        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /**
     * Advances a transaction through PENDING → PROCESSING → SUCCESS.
     * Simulates what the Kafka consumer will do in Task 12.
     */
    private void advanceToSuccess(UUID transactionId) {
        Transaction tx = transactionRepository.findById(transactionId).orElseThrow();
        tx.startProcessing();
        transactionRepository.save(tx);

        Transaction processing = transactionRepository.findById(transactionId).orElseThrow();
        processing.complete("test-gateway-ref-" + transactionId);
        transactionRepository.save(processing);
    }
}
