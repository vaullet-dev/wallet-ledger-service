package io.vaullet.ledger.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL for tests, started on demand by Testcontainers.
 *
 * <p>Testing this service against H2 would be worse than not testing it. Every load-bearing part of
 * ADR-004 is PostgreSQL-specific: {@code SELECT ... FOR UPDATE}, partial and functional unique
 * indexes, {@code NUMERIC(20,4)}, {@code TIMESTAMPTZ}, {@code make_interval}, and the
 * {@code DO INSTEAD NOTHING} rules that make {@code ledger_entries} append-only. An embedded
 * database would either reject the migration or, far worse, accept it and silently not enforce it.
 *
 * <p>{@code @ServiceConnection} is the piece that removes the boilerplate: Boot reads the
 * container's host, port and credentials and configures the {@code DataSource} itself. No
 * {@code @DynamicPropertySource} block, no property names to keep in sync.
 *
 * <p>The container is a singleton for the whole JVM because Spring caches application contexts
 * between test classes; a per-class container would restart PostgreSQL for every test class and
 * dominate the build time.
 *
 * <p>Pinned to the same tag {@code compose.yaml} uses, so a behaviour that passes locally and fails
 * in CI cannot be a version difference.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }
}
