package dev.cuong.payment.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class UserAccountIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ── GET /api/users/me ────────────────────────────────────────────────────

    @Test
    void should_return_profile_when_authenticated() throws Exception {
        String token = registerAndGetToken("alice", "alice@test.com", "password123");

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@test.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void should_reject_profile_request_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_reject_profile_request_when_token_is_invalid() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/accounts/me ─────────────────────────────────────────────────

    @Test
    void should_return_account_with_zero_balance_after_registration() throws Exception {
        String token = registerAndGetToken("bob", "bob@test.com", "password123");

        mockMvc.perform(get("/api/accounts/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    void should_reject_account_request_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_matching_user_id_in_profile_and_account() throws Exception {
        String token = registerAndGetToken("carol", "carol@test.com", "password123");

        MvcResult profileResult = mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult accountResult = mockMvc.perform(get("/api/accounts/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String profileId = objectMapper
                .readTree(profileResult.getResponse().getContentAsString())
                .get("id").asText();
        String accountUserId = objectMapper
                .readTree(accountResult.getResponse().getContentAsString())
                .get("userId").asText();

        assertThat(profileId).isEqualTo(accountUserId);
    }

    @Test
    void should_scope_profile_to_the_authenticated_user() throws Exception {
        String tokenDave = registerAndGetToken("dave", "dave@test.com", "password123");
        String tokenEve  = registerAndGetToken("eve",  "eve@test.com",  "password123");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenDave))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dave"));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenEve))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("eve"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, email, password))))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }
}
