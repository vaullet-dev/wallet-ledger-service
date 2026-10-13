package dev.vaullet.ledger.config.dao;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code ledger_config} row: ADR-004's deployment constants, written once at provisioning and
 * never updated. {@code max_hold_seconds} is read where it is enforced, in {@code LedgerRepository}.
 */
@Repository
public class LedgerConfigRepository {

    private final JdbcTemplate jdbc;

    public LedgerConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The deployment's currency (ISO 4217) and the decimal places of its minor unit. */
    public record LedgerConfigRow(String currency, int minorUnits) {}

    public Optional<LedgerConfigRow> find() {
        return jdbc.query("SELECT currency, minor_units FROM ledger_config",
                        (rs, i) -> new LedgerConfigRow(rs.getString("currency"), rs.getInt("minor_units")))
                .stream().findFirst();
    }

    /**
     * Writes the row unless there already is one; true when this call wrote it. A conflict rather
     * than check-then-insert: two replicas starting at once both try, one wins, and the other reads
     * the winner's row exactly as any later start would.
     */
    public boolean insertIfAbsent(String currency, int minorUnits) {
        return jdbc.update("""
                INSERT INTO ledger_config (currency, minor_units)
                VALUES (?, ?)
                ON CONFLICT (singleton) DO NOTHING
                """, currency, minorUnits) == 1;
    }
}
