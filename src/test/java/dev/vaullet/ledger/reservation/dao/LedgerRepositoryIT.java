package dev.vaullet.ledger.reservation.dao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journal, tested where it is the layer's own output rather than a side channel.
 *
 * <p>{@code ledger_entries} is the ledger's source of truth, so what gets written to it is a
 * statement about correctness and not an implementation detail. It is verified here, at the dao
 * seam, rather than by reaching past {@code LedgerService} into the database from a service test.
 *
 * <p>Testcontainers, not H2: every rule this file relies on — the append-only RULE, the
 * {@code (transaction_id, bucket_id, direction)} unique index, {@code NUMERIC(20,4)} — is
 * PostgreSQL behaviour that an embedded database would either fake or ignore.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerRepository.class)
@Testcontainers
class LedgerRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired LedgerRepository ledger;
    @Autowired JdbcTemplate jdbc;

    UUID account;
    UUID bucket;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                TRUNCATE ledger_entries, reservation_allocations, reservations,
                         balance_buckets, account_balances, ledger_config
                """);
        jdbc.update("INSERT INTO ledger_config (currency, minor_units) VALUES ('EUR', 2)");
        account = UUID.randomUUID();
        bucket = UUID.randomUUID();
        jdbc.update("INSERT INTO account_balances (account_id, posted_balance) VALUES (?, 100.0000)", account);
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, bucket_type, posted_balance,
                                             withdrawable, spend_priority)
                VALUES (?, ?, 'CASH', 100.0000, TRUE, 100)
                """, bucket, account);
    }

    @Test
    @DisplayName("a posted debit is readable back against its reservation")
    void postedDebitIsReadableBack() {
        UUID reservation = heldReservation(new BigDecimal("60.0000"));
        UUID transaction = UUID.randomUUID();

        ledger.postDebit(account, bucket, new BigDecimal("60.0000"), reservation, transaction);

        assertThat(ledger.entriesFor(reservation))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.bucketId()).isEqualTo(bucket);
                    assertThat(entry.direction()).isEqualTo("DEBIT");
                    assertThat(entry.amount()).isEqualByComparingTo("60.0000");
                    assertThat(entry.transactionId()).isEqualTo(transaction);
                });
    }

    private UUID heldReservation(BigDecimal amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reservations (reservation_id, account_id, amount, state, idempotency_key, expires_at)
                VALUES (?, ?, ?, 'HELD', ?, now() + make_interval(secs => 300))
                """, id, account, amount, "key-" + id);
        return id;
    }
}
