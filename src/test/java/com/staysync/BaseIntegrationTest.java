package com.staysync;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base Integration Test class using Testcontainers.
 *
 * WHY TESTCONTAINERS OVER H2 IN-MEMORY DATABASE:
 *
 * H2 Lies:
 * - H2 doesn't support PostgreSQL's CHECK constraints
 * - H2 doesn't understand PostgreSQL's date/range column types
 * - H2 doesn't enforce UNIQUE constraints the same way
 * - SQL syntax differences (e.g., GENERATED ALWAYS, DATERANGE) cause H2 failures
 *
 * Testcontainers Truth:
 * - Spins up real PostgreSQL 16 Docker container during test phase
 * - Real Redis for Redisson distributed lock testing
 * - Your integration tests behave identically to production
 * - No Docker setup required per developer — Testcontainers handles it
 *
 * Static Containers (@Container static field):
 * Containers are reused across ALL tests in the class hierarchy (not
 * restarted for every @Test method). This drastically speeds up the test suite.
 *
 * DynamicPropertySource:
 * After Testcontainers assigns random host ports, we inject the dynamic
 * JDBC URL, Redis host/port into the Spring application context,
 * overriding application.yml values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class BaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("staysync_test_db")
            .withUsername("test_user")
            .withPassword("test_password")
            .waitingFor(Wait.forListeningPort());

    @Container
    @SuppressWarnings("rawtypes")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    /**
     * Overrides Spring datasource and Redis config with Testcontainer-assigned ports.
     * Called before the Spring ApplicationContext starts.
     */
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Redis
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Disable Kafka for unit-level integration tests (test without real broker)
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }
}
