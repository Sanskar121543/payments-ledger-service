package io.github.sanskar121543.payments.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.IntegrationTest;
import io.github.sanskar121543.payments.support.TopicReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class EventPublishIT {

    @Value("${local.server.port}")
    int port;

    @Value("${payments.kafka.topic}")
    String topic;

    @Autowired
    KafkaConnectionDetails kafka;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void completedTransferIsPublishedKeyedBySenderWithEventIdAndCorrelationId() {
        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        ApiResponse transfer = api.transfer(alice, bob, 1_234);
        long transferId = transfer.longField("id");

        ConsumerRecord<String, String> record = recordFor(transferId);

        assertThat(record.key()).isEqualTo(String.valueOf(alice));
        JsonNode body = JsonMapper.builder().build().readTree(record.value());
        assertThat(body.get("amountCents").asLong()).isEqualTo(1_234);
        assertThat(body.get("toAccountId").asLong()).isEqualTo(bob);
        assertThat(body.get("kind").asString()).isEqualTo("TRANSFER");
        assertThat(header(record, TransferCompletedEvent.EVENT_ID_HEADER)).isEqualTo(body.get("eventId").asString());
        assertThat(header(record, TransferCompletedEvent.CORRELATION_ID_HEADER))
                .isEqualTo(transfer.header("X-Correlation-Id"));
    }

    @Test
    void failedTransfersPublishNothing() {
        long alice = api.fundedAccount("alice", 100);
        long bob = api.createAccount("bob");
        long failed = api.transfer(alice, bob, 5_000).longField("transferId");
        // Same sender, so same partition: once this later event is visible, an event for the failed
        // transfer would already have been read if one had been published.
        long later = api.transfer(alice, bob, 50).longField("id");

        List<ConsumerRecord<String, String>> records = TopicReader.readUntil(String.join(",", kafka.getBootstrapServers()), topic,
                rs -> rs.stream().anyMatch(r -> r.value().contains("\"transferId\":" + later + ",")),
                Duration.ofSeconds(30));

        assertThat(records).anyMatch(r -> r.value().contains("\"transferId\":" + later + ","));
        assertThat(records).noneMatch(r -> r.value().contains("\"transferId\":" + failed + ","));
    }

    private ConsumerRecord<String, String> recordFor(long transferId) {
        String marker = "\"transferId\":" + transferId + ",";
        return TopicReader.readUntil(String.join(",", kafka.getBootstrapServers()), topic,
                        rs -> rs.stream().anyMatch(r -> r.value().contains(marker)), Duration.ofSeconds(30))
                .stream()
                .filter(r -> r.value().contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No event for transfer " + transferId));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
