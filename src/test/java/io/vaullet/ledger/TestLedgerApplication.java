package io.vaullet.ledger;

import io.vaullet.common.test.PostgresContainerConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application from the IDE with its dependencies supplied by Testcontainers.
 *
 * <p>Start this class instead of {@link LedgerApplication} and you get a throwaway PostgreSQL,
 * migrated by Flyway, with no {@code docker compose up} and nothing to clean up afterwards. It is
 * the fastest path from a fresh clone to a running ledger, and it uses the same container
 * definition the integration tests do — so "works on my machine" and "passes in CI" mean the same
 * thing.
 *
 * <p>Running {@link LedgerApplication} directly will fail: it gets no profile, and
 * {@code SecurityConfig} refuses to start without {@code OAUTH2_ISSUER_URI}. That is the design,
 * not a bug.
 */
public final class TestLedgerApplication {

    private TestLedgerApplication() {}

    public static void main(String[] args) {
        SpringApplication.from(LedgerApplication::main)
                .with(PostgresContainerConfiguration.class)
                .run(concat(args, "--spring.profiles.active=local"));
    }

    private static String[] concat(String[] args, String extra) {
        var all = new String[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = extra;
        return all;
    }
}
