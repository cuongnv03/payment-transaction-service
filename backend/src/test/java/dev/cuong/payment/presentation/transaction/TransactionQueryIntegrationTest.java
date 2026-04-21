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
class TransactionQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;

    // ── List: happy path ──────────────────────────────────────────────────────

    @Test
    void should_return_own_transactions_newest_first() throws Exception {
        String senderToken   = registerAndGetToken("alice", "alice@test.com");
        String receiverToken = registerAndGetToken("bob",   "bob@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("1000.00"));

        // Create two transactions
        createTransaction(senderToken, toAccountId, "100.00");
        createTransaction(senderToken, toAccountId, "200.00");

        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void should_return_empty_list_when_user_has_no_transactions() throws Exception {
        String token = registerAndGetToken("carol", "carol@test.com");

        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // ── List: status filter ───────────────────────────────────────────────────

    @Test
    void should_filter_transactions_by_status() throws Exception {
        String senderToken   = registerAndGetToken("dave",  "dave@test.com");
        String receiverToken = registerAndGetToken("eve",   "eve@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("1000.00"));
        createTransaction(senderToken, toAccountId, "100.00");

        // PENDING filter matches
        mockMvc.perform(get("/api/transactions?status=PENDING")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        // SUCCESS filter returns empty (transaction is still PENDING)
        mockMvc.perform(get("/api/transactions?status=SUCCESS")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void should_reject_invalid_status_filter_value() throws Exception {
        String token = registerAndGetToken("frank", "frank@test.com");

        mockMvc.perform(get("/api/transactions?status=NOT_A_STATUS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ── List: pagination ──────────────────────────────────────────────────────

    @Test
    void should_paginate_results_correctly() throws Exception {
        String senderToken   = registerAndGetToken("grace", "grace@test.com");
        String receiverToken = registerAndGetToken("henry", "henry@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("5000.00"));

        // Create 3 transactions
        createTransaction(senderToken, toAccountId, "100.00");
        createTransaction(senderToken, toAccountId, "200.00");
        createTransaction(senderToken, toAccountId, "300.00");

        // Page 0, size 2 → 2 items, 2 total pages
        mockMvc.perform(get("/api/transactions?page=0&size=2")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));

        // Page 1, size 2 → 1 item
        mockMvc.perform(get("/api/transactions?page=1&size=2")
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.page").value(1));
    }

    // ── List: isolation ───────────────────────────────────────────────────────

    @Test
    void should_not_return_other_users_transactions() throws Exception {
        String aliceToken    = registerAndGetToken("alice2", "alice2@test.com");
        String bobToken      = registerAndGetToken("bob2",   "bob2@test.com");
        UUID aliceUserId     = extractUserId(aliceToken);
        UUID bobToAccountId  = accountRepository.findByUserId(extractUserId(bobToken)).orElseThrow().getId();

        fundAccount(aliceUserId, new BigDecimal("500.00"));
        createTransaction(aliceToken, bobToAccountId, "100.00");

        // Bob queries his own transactions — should see none (he's only the receiver)
        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ── Single fetch: happy path ──────────────────────────────────────────────

    @Test
    void should_return_transaction_by_id() throws Exception {
        String senderToken   = registerAndGetToken("ivan",  "ivan@test.com");
        String receiverToken = registerAndGetToken("julia", "julia@test.com");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));
        String txId = createTransaction(senderToken, toAccountId, "150.00");

        mockMvc.perform(get("/api/transactions/" + txId)
                        .header("Authorization", "Bearer " + senderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.toAccountId").value(toAccountId.toString()));
    }

    // ── Single fetch: error cases ─────────────────────────────────────────────

    @Test
    void should_return_404_when_transaction_does_not_exist() throws Exception {
        String token = registerAndGetToken("kevin", "kevin@test.com");

        mockMvc.perform(get("/api/transactions/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_404_when_fetching_another_users_transaction() throws Exception {
        String aliceToken    = registerAndGetToken("alice3", "alice3@test.com");
        String bobToken      = registerAndGetToken("bob3",   "bob3@test.com");
        UUID aliceUserId     = extractUserId(aliceToken);
        UUID bobToAccountId  = accountRepository.findByUserId(extractUserId(bobToken)).orElseThrow().getId();

        fundAccount(aliceUserId, new BigDecimal("500.00"));
        String txId = createTransaction(aliceToken, bobToAccountId, "100.00");

        // Bob tries to fetch Alice's transaction — must get 404, not 403
        mockMvc.perform(get("/api/transactions/" + txId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_401_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/transactions/" + UUID.randomUUID()))
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

    /** Creates a transaction and returns its ID. */
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
}
