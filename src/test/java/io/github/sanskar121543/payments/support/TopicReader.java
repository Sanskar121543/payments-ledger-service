package io.github.sanskar121543.payments.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning with a throwaway consumer group, like an independent observer. */
public final class TopicReader {

    private TopicReader() {
    }

    public static List<ConsumerRecord<String, String>> readUntil(String bootstrapServers, String topic,
            Predicate<List<ConsumerRecord<String, String>>> done, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline) && !done.test(records)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        return records;
    }
}
