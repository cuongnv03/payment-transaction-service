package dev.cuong.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Application context smoke test.
 *
 * <p>Verifies that the Spring DI container starts without errors — catches missing beans,
 * circular dependencies, and invalid configuration early.
 *
 * <p>Uses H2 in-memory DB + disabled Flyway so no external infrastructure is required.
 * Full integration tests (Testcontainers with real Postgres/Redis/Kafka) live in
 * the integration package starting from Task 22.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Use H2 in-memory database — no Postgres needed for wiring check
        "spring.datasource.url=jdbc:h2:mem:smoketest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",

        // Disable Flyway — schema not set up yet (Task 02)
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",

        // Exclude Redisson (RedissonClient is eagerly initialized; no Redis in smoke test).
        // Note: Spring Boot 3 auto-config uses RedissonAutoConfigurationV2 (verified from JAR manifest).
        // Exclude Kafka (KafkaAdmin also tries to connect at startup).
        "spring.autoconfigure.exclude=" +
                "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
class PaymentTransactionServiceApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails with a descriptive error.
        // No assertions needed — context startup is the assertion.
    }
}
