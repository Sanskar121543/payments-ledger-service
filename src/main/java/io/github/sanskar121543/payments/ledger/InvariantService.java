package io.github.sanskar121543.payments.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvariantService {

    private final JdbcTemplate jdbc;

    public InvariantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // REPEATABLE READ makes every query below see the same snapshot, so the checks agree with each other.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvariantReport check() {
        long sum = count("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries");
        long mismatches = count("""
                SELECT COUNT(*) FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount_cents) AS total FROM ledger_entries GROUP BY account_id) e
                       ON e.account_id = a.id
                WHERE a.balance_cents <> COALESCE(e.total, 0)
                """);
        long unbalanced = count("""
                SELECT COUNT(*) FROM transfers t
                LEFT JOIN (SELECT transfer_id, COUNT(*) AS n, SUM(amount_cents) AS total
                           FROM ledger_entries GROUP BY transfer_id) e
                       ON e.transfer_id = t.id
                WHERE CASE WHEN t.status = 'COMPLETED' THEN COALESCE(e.n, 0) <> 2 OR e.total <> 0
                           ELSE COALESCE(e.n, 0) <> 0 END
                """);
        long negative = count("SELECT COUNT(*) FROM accounts WHERE type = 'CUSTOMER' AND balance_cents < 0");
        return InvariantReport.of(sum, mismatches, unbalanced, negative,
                count("SELECT COUNT(*) FROM accounts"),
                count("SELECT COUNT(*) FROM transfers"),
                count("SELECT COUNT(*) FROM ledger_entries"));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
