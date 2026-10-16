package io.github.sanskar121543.payments.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.TopicReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The scenario that lost the event under publish-after-commit (phase 4): the transfer commits while
 * Kafka is unreachable and then every app instance dies. With the outbox, the event is already in the
 * database, so the next instance to start publishes it.
 */
class OutboxCrashIT {

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
    void anEventCommittedBeforeACrashIsStillDeliveredExactlyOnceToTheFeed() {
        ApiClient app2 = topology.direct("app2");
        long alice = app2.fundedAccount("alice", 5_000);
        long bob = app2.createAccount("bob");
        JdbcTemplate jdbc = topology.jdbc();

        topology.pauseKafka();
        ApiResponse transfer = app2.transfer(alice, bob, 777);
        assertThat(transfer.status()).as("the API no longer waits for Kafka").isEqualTo(201);
        long transferId = transfer.longField("id");
        assertThat(unpublishedOutboxRows(jdbc, transferId)).isEqualTo(1);

        LedgerTopology.kill(topology.app("app1"));
        LedgerTopology.kill(topology.app("app2"));
        topology.unpauseKafka();
        topology.startExtraApp("app3", Map.of("PAYMENTS_DEMO_ENABLED", "false"));

        await().atMost(Duration.ofSeconds(60)).until(() -> unpublishedOutboxRows(jdbc, transferId) == 0);
        List<ConsumerRecord<String, String>> topic = TopicReader.readUntil(topology.kafkaBootstrapServers(),
                "transfer.completed", rs -> rs.stream().anyMatch(r -> mentions(r, transferId)),
                Duration.ofSeconds(60));
        assertThat(topic).anyMatch(r -> mentions(r, transferId));
        await().atMost(Duration.ofSeconds(90)).until(() -> feedRows(jdbc, transferId) >= 1);
        assertThat(feedRows(jdbc, transferId)).as("duplicates from at-least-once delivery are dropped").isEqualTo(1);
    }

    private static long unpublishedOutboxRows(JdbcTemplate jdbc, long transferId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND payload LIKE ?",
                Long.class, "%\"transferId\":" + transferId + ",%");
    }

    private static long feedRows(JdbcTemplate jdbc, long transferId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_feed WHERE transfer_id = ?", Long.class, transferId);
    }

    private static boolean mentions(ConsumerRecord<String, String> record, long transferId) {
        return record.value().contains("\"transferId\":" + transferId + ",");
    }
}
