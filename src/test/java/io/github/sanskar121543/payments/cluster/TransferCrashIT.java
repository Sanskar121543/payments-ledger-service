package io.github.sanskar121543.payments.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The proof test for "the server crashes mid-transfer": app1 is SIGKILLed after it has inserted the
 * transfer, both ledger entries and the outbox row, but before it could commit. Nothing may survive,
 * and retrying with the same Idempotency-Key on another instance must produce exactly one transfer.
 */
class TransferCrashIT {

    private static final Logger log = LoggerFactory.getLogger(TransferCrashIT.class);

    static LedgerTopology topology;

    @BeforeAll
    static void start() {
        // app1 waits up to 5 minutes for locks, so the pause below can only end with the kill.
        topology = LedgerTopology.start(List.of("app1", "app2"), false,
                Map.of("PAYMENTS_DB_LOCK_TIMEOUT", "5min"));
    }

    @AfterAll
    static void stop() {
        topology.close();
    }

    @Test
    void appKilledMidTransferLeavesNoPartialWrites() throws Exception {
        ApiClient survivor = topology.direct("app2");
        long alice = survivor.fundedAccount("alice", 5_000);
        long bob = survivor.createAccount("bob");
        String key = UUID.randomUUID().toString();
        JdbcTemplate jdbc = topology.jdbc();

        CompletableFuture<Object> request;
        try (Connection blocker = DriverManager.getConnection(topology.jdbcUrl(), topology.dbUser(),
                topology.dbPassword())) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                // SHARE mode still allows the app's row locks, inserts and foreign-key checks, but no
                // UPDATE of accounts. Balance updates are the last writes before COMMIT, so app1 stops
                // with everything else already written.
                statement.execute("LOCK TABLE accounts IN SHARE MODE");
            }

            ApiClient victim = topology.direct("app1");
            request = CompletableFuture.supplyAsync(() -> {
                try {
                    return victim.transfer(alice, bob, 1_234, key);
                } catch (RuntimeException connectionDropped) {
                    return connectionDropped;
                }
            });

            await().atMost(Duration.ofSeconds(20)).until(() -> app1IsWaitingOnTheAccountsUpdate(jdbc));
            List<String> writtenTables = jdbc.queryForList("""
                    SELECT DISTINCT l.relation::regclass::text FROM pg_locks l
                    JOIN pg_stat_activity a ON a.pid = l.pid
                    WHERE a.application_name = 'app1' AND l.mode = 'RowExclusiveLock' AND l.granted
                      AND l.relation IS NOT NULL
                    """, String.class);
            log.info("app1 holds write locks on {} at the moment it is killed", writtenTables);
            assertThat(writtenTables).as("app1 really was mid-transfer")
                    .contains("transfers", "ledger_entries", "outbox_events", "idempotency_keys")
                    .doesNotContain("accounts");

            LedgerTopology.kill(topology.app("app1"));
            blocker.rollback();
        }

        assertThat(request.get(30, TimeUnit.SECONDS)).as("the client saw the connection drop, not an HTTP answer")
                .isInstanceOf(RuntimeException.class);
        await().atMost(Duration.ofSeconds(20)).until(() -> backendsOf(jdbc, "app1") == 0);

        assertThat(count(jdbc, "SELECT COUNT(*) FROM transfers WHERE from_account_id = ?", alice)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?", bob)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?", key)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM outbox_events WHERE message_key = ?", String.valueOf(alice)))
                .isZero();
        assertThat(survivor.balance(alice)).isEqualTo(5_000);
        assertThat(survivor.balance(bob)).isZero();
        assertThat(survivor.invariants().get("ok").asBoolean()).isTrue();

        ApiResponse retried = survivor.transfer(alice, bob, 1_234, key);
        ApiResponse replayed = survivor.transfer(alice, bob, 1_234, key);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.textField("servedBy")).isEqualTo("app2");
        assertThat(replayed.rawBody()).isEqualTo(retried.rawBody());
        assertThat(count(jdbc, "SELECT COUNT(*) FROM transfers WHERE from_account_id = ?", alice)).isEqualTo(1);
        assertThat(survivor.balance(bob)).isEqualTo(1_234);
    }

    private static boolean app1IsWaitingOnTheAccountsUpdate(JdbcTemplate jdbc) {
        return count(jdbc, """
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE application_name = 'app1' AND wait_event_type = 'Lock' AND query ILIKE 'update accounts%'
                """) == 1;
    }

    private static long backendsOf(JdbcTemplate jdbc, String app) {
        return count(jdbc, "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name = ?", app);
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
