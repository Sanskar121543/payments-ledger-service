package io.github.sanskar121543.payments.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.TopicReader;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Documents the known gap of publish-after-commit: if the app dies after the database commit but before
 * Kafka has the record, the event is gone for good. The money is fine; the activity feed never hears
 * about the transfer. Phase 6 replaces this with a transactional outbox and OutboxCrashIT.
 */
class PublishAfterCommitCrashIT {

    static LedgerTopology topology;

    @BeforeAll
    static void start() {
        topology = LedgerTopology.start(List.of("app1", "app2"), false);
    }

    @AfterAll
    static void stop() {
        topology.close();
    }

    @Test
    void anEventIsLostWhenTheAppDiesBetweenCommitAndPublish() {
        ApiClient setup = topology.direct("app1");
        long alice = setup.fundedAccount("alice", 5_000);
        long bob = setup.createAccount("bob");
        JdbcTemplate jdbc = topology.jdbc();

        // app2 has never published anything, so its producer has no connection to Kafka yet. With the
        // broker frozen, its first send() blocks after the commit: exactly the window we want to crash in.
        topology.pauseKafka();
        ApiClient victim = topology.direct("app2");
        CompletableFuture.runAsync(() -> {
            try {
                victim.transfer(alice, bob, 777, UUID.randomUUID().toString());
            } catch (RuntimeException expectedWhenKilled) {
                // The connection drops when app2 is killed.
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> completedTransfersFrom(jdbc, alice) == 1);
        LedgerTopology.kill(topology.app("app2"));
        topology.unpauseKafka();

        long lostTransferId = jdbc.queryForObject(
                "SELECT id FROM transfers WHERE from_account_id = ? AND status = 'COMPLETED'", Long.class, alice);
        long laterTransferId = setup.transfer(alice, bob, 1).longField("id");

        List<ConsumerRecord<String, String>> topic = TopicReader.readUntil(topology.kafkaBootstrapServers(),
                "transfer.completed", rs -> rs.stream().anyMatch(r -> mentions(r, laterTransferId)),
                Duration.ofSeconds(60));
        assertThat(topic).as("the later event from the same sender arrived").anyMatch(r -> mentions(r, laterTransferId));
        assertThat(topic).as("the event for the committed transfer was never published")
                .noneMatch(r -> mentions(r, lostTransferId));

        await().atMost(Duration.ofSeconds(60)).until(() -> feedRows(jdbc, laterTransferId) == 1);
        assertThat(feedRows(jdbc, lostTransferId)).as("so the activity feed never hears about it").isZero();
        assertThat(setup.invariants().get("ok").asBoolean()).as("while the money itself is fine").isTrue();
        assertThat(setup.balance(bob)).isEqualTo(778);
    }

    private static long completedTransfersFrom(JdbcTemplate jdbc, long account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transfers WHERE from_account_id = ? AND status = 'COMPLETED'",
                Long.class, account);
    }

    private static long feedRows(JdbcTemplate jdbc, long transferId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_feed WHERE transfer_id = ?", Long.class, transferId);
    }

    private static boolean mentions(ConsumerRecord<String, String> record, long transferId) {
        return record.value().contains("\"transferId\":" + transferId + ",");
    }
}
