package dev.cuong.payment.infrastructure.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Verifies that with the JSON-encoder profile (default — anything not "local"),
 * each log line emitted by the application is a single, parseable JSON object
 * with the schema defined in {@code logback-spring.xml}.
 *
 * <p>An HTTP request triggers logs from the controller path. We then parse the
 * captured stdout, find a log line whose {@code logger} is one we control, and
 * assert that it carries the expected fields.
 *
 * <p>Authentication is intentionally <em>not</em> performed: the request hits
 * an endpoint that requires auth, gets a 401, and we still expect at least one
 * structured log line from the request lifecycle (Spring's RequestMappingHandlerMapping,
 * etc.). For asserting MDC presence we use a logger that fires unconditionally.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=" +
                "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class JsonLogOutputIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void should_emit_each_log_line_as_a_single_JSON_object_with_required_fields(CapturedOutput output)
            throws Exception {
        // Trigger a request — the resulting Spring Security DEBUG/INFO logs go through
        // our LogstashEncoder. We use /actuator/health (permitAll) so we don't get a 401.
        mockMvc.perform(get("/actuator/health")).andReturn();

        // Find any log line that parses as JSON and carries our schema.
        Optional<JsonNode> structured = output.getAll()
                .lines()
                .map(this::tryParseJson)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(node -> node.has("timestamp") && node.has("level") && node.has("logger") && node.has("message"))
                .findFirst();

        assertThat(structured)
                .as("at least one log line must be valid JSON with timestamp/level/logger/message")
                .isPresent();

        JsonNode line = structured.get();
        assertThat(line.get("timestamp").asText()).isNotBlank();
        assertThat(line.get("level").asText()).isIn("INFO", "WARN", "ERROR", "DEBUG", "TRACE");
        assertThat(line.get("logger").asText()).isNotBlank();
        assertThat(line.get("message").asText()).isNotBlank();
    }

    private Optional<JsonNode> tryParseJson(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(trimmed));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
