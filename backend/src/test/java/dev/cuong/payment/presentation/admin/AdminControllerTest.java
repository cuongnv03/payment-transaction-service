package dev.cuong.payment.presentation.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.vo.UserRole;
import dev.cuong.payment.infrastructure.persistence.entity.DeadLetterEventJpaEntity;
import dev.cuong.payment.infrastructure.persistence.entity.UserJpaEntity;
import dev.cuong.payment.infrastructure.persistence.repository.DeadLetterEventJpaRepository;
import dev.cuong.payment.infrastructure.persistence.repository.UserJpaRepository;
import dev.cuong.payment.presentation.auth.LoginRequest;
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

/**
 * Integration tests for {@link AdminController}.
 *
 * <p>Uses real Postgres. Kafka and Redis are excluded — the admin service does not need
 * Kafka to list/retry DLQ events (retry re-publishes via the no-op {@code EventPublisher}).
 *
 * <p>Admin users are created by registering a normal user and then updating the role
 * directly via {@link UserJpaRepository}. The user re-authenticates to receive a JWT
 * that carries the {@code ROLE_ADMIN} authority.
 */
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
class AdminControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired DeadLetterEventJpaRepository deadLetterEventJpaRepository;
    @Autowired AccountRepository accountRepository;

    // ── DLQ endpoint ──────────────────────────────────────────────────────────

    @Test
    void should_return_empty_dlq_list_when_no_events_exist() throws Exception {
        String adminToken = createAdminAndGetToken("adm1", "adm1@test.com", "password");

        mockMvc.perform(get("/api/admin/dlq")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void should_return_dlq_event_when_entry_is_seeded() throws Exception {
        String adminToken = createAdminAndGetToken("adm2", "adm2@test.com", "password");

        DeadLetterEventJpaEntity entry = DeadLetterEventJpaEntity.builder()
                .topic("payment.transaction.events")
                .kafkaPartition(0)
                .kafkaOffset(42L)
                .payload("{\"transactionId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PROCESSING\"}")
                .errorMessage("Simulated consumer failure")
                .build();
        deadLetterEventJpaRepository.saveAndFlush(entry);

        mockMvc.perform(get("/api/admin/dlq")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].topic").value("payment.transaction.events"))
                .andExpect(jsonPath("$.data[0].errorMessage").value("Simulated consumer failure"));
    }

    // ── DLQ retry endpoint ────────────────────────────────────────────────────

    @Test
    void should_return_404_when_retry_dlq_event_id_does_not_exist() throws Exception {
        String adminToken = createAdminAndGetToken("adm3", "adm3@test.com", "password");

        mockMvc.perform(post("/api/admin/dlq/" + UUID.randomUUID() + "/retry")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void should_return_204_and_mark_resolved_when_retry_succeeds() throws Exception {
        String adminToken = createAdminAndGetToken("adm4", "adm4@test.com", "password");

        // Create a sender with funded account and a receiver
        String senderToken   = registerAndGetToken("tx-sender", "sender@test.com", "password");
        String receiverToken = registerAndGetToken("tx-recv",   "recv@test.com",   "password");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));

        // Create a transaction to get a real transactionId
        MvcResult txResult = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "100.00")))
                .andExpect(status().isCreated())
                .andReturn();
        String transactionId = objectMapper.readTree(txResult.getResponse().getContentAsString())
                .get("id").asText();

        // Seed a DLQ event pointing to that transaction
        String payload = String.format(
                "{\"transactionId\":\"%s\",\"eventType\":\"PROCESSING\",\"fromAccountId\":\"%s\"," +
                "\"toAccountId\":\"%s\",\"amount\":100.00,\"currency\":\"USD\",\"status\":\"PROCESSING\"," +
                "\"retryCount\":0,\"occurredAt\":\"2024-01-01T00:00:00Z\"}",
                transactionId, senderUserId, toAccountId);

        DeadLetterEventJpaEntity dlqEntry = DeadLetterEventJpaEntity.builder()
                .topic("payment.transaction.events")
                .kafkaPartition(0)
                .kafkaOffset(100L)
                .payload(payload)
                .errorMessage("Simulated consumer failure for retry test")
                .build();
        deadLetterEventJpaRepository.saveAndFlush(dlqEntry);
        UUID dlqId = dlqEntry.getId();

        // Retry the DLQ event
        mockMvc.perform(post("/api/admin/dlq/" + dlqId + "/retry")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Verify the event is marked resolved
        DeadLetterEventJpaEntity resolved = deadLetterEventJpaRepository.findById(dlqId).orElseThrow();
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolvedBy()).isEqualTo("admin-retry");
    }

    // ── Transactions endpoint ─────────────────────────────────────────────────

    @Test
    void should_return_all_transactions_across_users_when_admin() throws Exception {
        String adminToken    = createAdminAndGetToken("adm5", "adm5@test.com", "password");
        String senderToken   = registerAndGetToken("tx-s2", "txs2@test.com", "password");
        String receiverToken = registerAndGetToken("tx-r2", "txr2@test.com", "password");
        UUID senderUserId    = extractUserId(senderToken);
        UUID toAccountId     = accountRepository.findByUserId(extractUserId(receiverToken)).orElseThrow().getId();

        fundAccount(senderUserId, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody(toAccountId, "50.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/transactions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));
    }

    // ── Circuit breaker endpoint ──────────────────────────────────────────────

    @Test
    void should_return_circuit_breaker_status_when_admin() throws Exception {
        String adminToken = createAdminAndGetToken("adm6", "adm6@test.com", "password");

        mockMvc.perform(get("/api/admin/circuit-breaker")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("payment-gateway"))
                .andExpect(jsonPath("$.state").isNotEmpty());
    }

    // ── Authorization ─────────────────────────────────────────────────────────

    @Test
    void should_return_403_when_regular_user_calls_admin_endpoint() throws Exception {
        String userToken = registerAndGetToken("regular", "regular@test.com", "password");

        mockMvc.perform(get("/api/admin/dlq")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void should_return_401_when_no_jwt_on_admin_endpoint() throws Exception {
        mockMvc.perform(get("/api/admin/dlq"))
                .andExpect(status().isUnauthorized());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createAdminAndGetToken(String username, String email, String password) throws Exception {
        String token = registerAndGetToken(username, email, password);
        UUID userId = extractUserId(token);

        // Promote the user to ADMIN within the test transaction so MockMvc calls see the update
        UserJpaEntity user = userJpaRepository.findById(userId).orElseThrow();
        user.setRole(UserRole.ADMIN);
        userJpaRepository.saveAndFlush(user);

        // Re-login to get a JWT that carries ROLE_ADMIN
        return login(username, password);
    }

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, email, password))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
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
