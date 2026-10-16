package io.github.sanskar121543.payments.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sanskar121543.payments.events.TransferCompletedEvent;
import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ActivityConsumerIT {

    @Value("${local.server.port}")
    int port;

    @Value("${payments.kafka.topic}")
    String topic;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper jsonMapper;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void completedTransfersShowUpInTheFeedWithWhoServedAndWhoConsumed() {
        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        long transferId = api.transfer(alice, bob, 700).longField("id");

        JsonNode item = awaitFeedItems(transferId, 1).get(0);

        assertThat(item.get("amountCents").asLong()).isEqualTo(700);
        assertThat(item.get("servedBy").asString()).isEqualTo("test");
        assertThat(item.get("consumedBy").asString()).isEqualTo("test");
        assertThat(item.get("largeAmount").asBoolean()).isFalse();
    }

    @Test
    void aRedeliveredEventIsRecordedOnlyOnce() {
        long transferId = 900_000_000L + (System.nanoTime() % 1_000_000);
        TransferCompletedEvent event = new TransferCompletedEvent(UUID.randomUUID(), transferId, "TRANSFER", 1L, 2L,
                100, "test", null, Instant.now());
        String json = jsonMapper.writeValueAsString(event);

        kafka.send(topic, event.messageKey(), json).join();
        kafka.send(topic, event.messageKey(), json).join();
        awaitFeedItems(transferId, 1);

        long marker = api.fundedAccount("marker", 10);
        long sameSenderLater = api.transfer(ApiClient.FUNDING_ACCOUNT_ID, marker, 5).longField("id");
        awaitFeedItems(sameSenderLater, 1);
        assertThat(feedFor(transferId)).hasSize(1);
    }

    @Test
    void largeAmountsAreFlagged() {
        long whale = api.fundedAccount("whale", 500_000);
        long bob = api.createAccount("bob");

        long transferId = api.transfer(whale, bob, 100_000).longField("id");

        assertThat(awaitFeedItems(transferId, 1).get(0).get("largeAmount").asBoolean()).isTrue();
    }

    @Test
    void theSixthSendWithinAMinuteIsFlaggedForVelocity() {
        long busy = api.fundedAccount("busy", 10_000);
        long bob = api.createAccount("bob");

        long[] ids = new long[6];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = api.transfer(busy, bob, 10).longField("id");
        }

        for (int i = 0; i < 5; i++) {
            assertThat(awaitFeedItems(ids[i], 1).get(0).get("highVelocity").asBoolean()).isFalse();
        }
        assertThat(awaitFeedItems(ids[5], 1).get(0).get("highVelocity").asBoolean()).isTrue();
    }

    private List<JsonNode> awaitFeedItems(long transferId, int expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> feedFor(transferId).size() >= expected);
        return feedFor(transferId);
    }

    private List<JsonNode> feedFor(long transferId) {
        JsonNode items = api.get("/v1/activity?transferId=" + transferId).body();
        return StreamSupport.stream(items.spliterator(), false).toList();
    }
}
