package io.github.sanskar121543.payments.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class OutboxIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void aCompletedTransferWritesOneOutboxRowThatTheRelayPublishes() {
        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        long transferId = api.transfer(alice, bob, 250).longField("id");

        await().atMost(Duration.ofSeconds(20)).until(() -> outboxRows(transferId).stream()
                .allMatch(row -> row.get("published_at") != null) && outboxRows(transferId).size() == 1);

        Map<String, Object> row = outboxRows(transferId).getFirst();
        assertThat(row.get("message_key")).isEqualTo(String.valueOf(alice));
        assertThat(row.get("topic")).isEqualTo("transfer.completed");
    }

    @Test
    void failedTransfersWriteNoOutboxRow() {
        long alice = api.fundedAccount("alice", 100);
        long bob = api.createAccount("bob");

        long failed = api.transfer(alice, bob, 5_000).longField("transferId");

        assertThat(outboxRows(failed)).isEmpty();
    }

    @Test
    void retriesOfTheSameRequestWriteNoExtraEvents() {
        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        long transferId = api.transfer(alice, bob, 100, key).longField("id");
        api.transfer(alice, bob, 100, key);
        api.transfer(alice, bob, 100, key);

        assertThat(outboxRows(transferId)).hasSize(1);
    }

    private List<Map<String, Object>> outboxRows(long transferId) {
        return jdbc.queryForList("SELECT message_key, topic, published_at FROM outbox_events WHERE payload LIKE ?",
                "%\"transferId\":" + transferId + ",%");
    }
}
