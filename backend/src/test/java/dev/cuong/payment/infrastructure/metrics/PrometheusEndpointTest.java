package dev.cuong.payment.infrastructure.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Prometheus scrape endpoint exposes our custom business metrics.
 *
 * <p>Three metric families MUST be present immediately on startup (no traffic required):
 * <ul>
 *   <li>{@code transactions_created_total} — registered by {@link MicrometerTransactionMetrics}
 *   <li>{@code transactions_processing_duration_seconds_count} — registered by the same component
 *   <li>{@code resilience4j_circuitbreaker_state{name="payment-gateway"}} — auto-registered by
 *       {@code resilience4j-spring-boot3} when Micrometer is on the classpath
 * </ul>
 *
 * <p>Per-status processed counters ({@code transactions_processed_total{status="..."}}) appear
 * lazily on first increment — they are NOT asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=" +
                "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PrometheusEndpointTest {

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

    @Test
    void should_expose_custom_business_metrics_at_prometheus_endpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        assertThat(body)
                .as("transactions.created counter must be exposed")
                .contains("transactions_created_total");

        assertThat(body)
                .as("transactions.processing.duration timer must be exposed (count series)")
                .contains("transactions_processing_duration_seconds_count");

        assertThat(body)
                .as("resilience4j circuit breaker state gauge must be auto-registered for payment-gateway")
                .contains("resilience4j_circuitbreaker_state")
                .contains("name=\"payment-gateway\"");
    }

    @Test
    void should_be_publicly_accessible_without_authentication() throws Exception {
        // Prometheus scrapers must reach this endpoint without a JWT.
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }
}
