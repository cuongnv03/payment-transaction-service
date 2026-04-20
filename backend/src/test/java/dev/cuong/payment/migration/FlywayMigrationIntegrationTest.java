package dev.cuong.payment.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that all Flyway migrations apply cleanly and that critical database
 * constraints (money non-negative, valid status values) are enforced at the
 * storage layer — independent of application logic.
 *
 * <p>Uses a real PostgreSQL container so the SQL is executed against the same
 * engine as production. H2 compatibility mode cannot catch all PG-specific
 * constraint or syntax errors.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    // Redis and Kafka are not needed for schema verification.
    "spring.autoconfigure.exclude=" +
        "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    "spring.flyway.enabled=true",
    "spring.flyway.validate-on-migrate=true",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class FlywayMigrationIntegrationTest {

    // Static container shared across all tests in this class — one PostgreSQL
    // instance is started per test class, not per test method.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_apply_all_migrations_and_create_expected_tables() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename",
            String.class
        );

        assertThat(tables).containsExactlyInAnyOrder(
            "users",
            "accounts",
            "transactions",
            "idempotency_keys",
            "audit_logs",
            "dead_letter_events",
            "flyway_schema_history"
        );
    }

    @Test
    void should_record_all_four_migrations_in_flyway_history() {
        List<String> appliedVersions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
            String.class
        );

        assertThat(appliedVersions).containsExactly("1", "2", "3", "4");
    }

    @Test
    @Transactional
    void should_reject_negative_account_balance() {
        jdbcTemplate.execute("""
            INSERT INTO users (id, username, email, password_hash, role)
            VALUES ('10000000-0000-0000-0000-000000000001',
                    'balanceuser', 'balance@test.com', 'hash', 'USER')
            """);

        assertThatThrownBy(() -> jdbcTemplate.execute("""
            INSERT INTO accounts (user_id, balance)
            VALUES ('10000000-0000-0000-0000-000000000001', -0.0001)
            """))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_accounts_balance");
    }

    @Test
    @Transactional
    void should_reject_invalid_transaction_status() {
        jdbcTemplate.execute("""
            INSERT INTO users (id, username, email, password_hash, role)
            VALUES ('20000000-0000-0000-0000-000000000001',
                    'statususer', 'status@test.com', 'hash', 'USER')
            """);
        jdbcTemplate.execute("""
            INSERT INTO accounts (id, user_id, balance)
            VALUES ('20000000-0000-0000-0000-000000000001',
                    '20000000-0000-0000-0000-000000000001', 1000.00)
            """);

        assertThatThrownBy(() -> jdbcTemplate.execute("""
            INSERT INTO transactions (user_id, account_id, amount, type, status)
            VALUES ('20000000-0000-0000-0000-000000000001',
                    '20000000-0000-0000-0000-000000000001',
                    100.00, 'PAYMENT', 'BOGUS_STATUS')
            """))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_transactions_status");
    }

    @Test
    @Transactional
    void should_reject_invalid_transaction_type() {
        jdbcTemplate.execute("""
            INSERT INTO users (id, username, email, password_hash, role)
            VALUES ('30000000-0000-0000-0000-000000000001',
                    'typeuser', 'type@test.com', 'hash', 'USER')
            """);
        jdbcTemplate.execute("""
            INSERT INTO accounts (id, user_id, balance)
            VALUES ('30000000-0000-0000-0000-000000000001',
                    '30000000-0000-0000-0000-000000000001', 500.00)
            """);

        assertThatThrownBy(() -> jdbcTemplate.execute("""
            INSERT INTO transactions (user_id, account_id, amount, type)
            VALUES ('30000000-0000-0000-0000-000000000001',
                    '30000000-0000-0000-0000-000000000001',
                    50.00, 'WIRE_TRANSFER')
            """))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_transactions_type");
    }

    @Test
    @Transactional
    void should_reject_zero_or_negative_transaction_amount() {
        jdbcTemplate.execute("""
            INSERT INTO users (id, username, email, password_hash, role)
            VALUES ('40000000-0000-0000-0000-000000000001',
                    'amountuser', 'amount@test.com', 'hash', 'USER')
            """);
        jdbcTemplate.execute("""
            INSERT INTO accounts (id, user_id, balance)
            VALUES ('40000000-0000-0000-0000-000000000001',
                    '40000000-0000-0000-0000-000000000001', 200.00)
            """);

        assertThatThrownBy(() -> jdbcTemplate.execute("""
            INSERT INTO transactions (user_id, account_id, amount, type)
            VALUES ('40000000-0000-0000-0000-000000000001',
                    '40000000-0000-0000-0000-000000000001',
                    0.00, 'PAYMENT')
            """))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_transactions_amount");
    }

    @Test
    @Transactional
    void should_enforce_one_account_per_user() {
        jdbcTemplate.execute("""
            INSERT INTO users (id, username, email, password_hash, role)
            VALUES ('50000000-0000-0000-0000-000000000001',
                    'oneaccuser', 'oneacc@test.com', 'hash', 'USER')
            """);
        jdbcTemplate.execute("""
            INSERT INTO accounts (id, user_id, balance)
            VALUES ('50000000-0000-0000-0000-000000000001',
                    '50000000-0000-0000-0000-000000000001', 100.00)
            """);

        assertThatThrownBy(() -> jdbcTemplate.execute("""
            INSERT INTO accounts (id, user_id, balance)
            VALUES ('50000000-0000-0000-0000-000000000002',
                    '50000000-0000-0000-0000-000000000001', 200.00)
            """))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uq_accounts_user_id");
    }
}
