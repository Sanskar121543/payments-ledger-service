package io.github.sanskar121543.payments.outbox;

import io.github.sanskar121543.payments.common.SingletonJobLock;
import io.github.sanskar121543.payments.events.TransferCompletedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes committed outbox rows to Kafka. Delivery is at-least-once: if we crash after Kafka
 * acknowledged a record but before published_at is committed, the record is sent again and the consumer
 * drops the duplicate by event_id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final SingletonJobLock jobLock;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, SingletonJobLock jobLock,
            @Value("${payments.outbox.batch-size}") int batchSize,
            @Value("${payments.outbox.send-timeout}") Duration sendTimeout) {
        this.repository = repository;
        this.kafka = kafka;
        this.jobLock = jobLock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /**
     * One relay at a time across all instances (advisory lock), oldest row first, and the batch stops at
     * the first record Kafka does not acknowledge, so a later event never overtakes an earlier one.
     */
    @Scheduled(fixedDelayString = "${payments.outbox.poll-interval}")
    @Transactional
    public int relayBatch() {
        if (!jobLock.tryAcquire(SingletonJobLock.OUTBOX_RELAY)) {
            return 0;
        }
        List<OutboxEvent> batch = repository.findByPublishedAtIsNullOrderByIdAsc(Limit.of(batchSize));
        int published = 0;
        for (OutboxEvent event : batch) {
            if (!send(event)) {
                break;
            }
            event.markPublished(Instant.now());
            published++;
        }
        return published;
    }

    private boolean send(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getMessageKey(),
                event.getPayload());
        record.headers().add(TransferCompletedEvent.EVENT_ID_HEADER,
                event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        if (event.getCorrelationId() != null) {
            record.headers().add(TransferCompletedEvent.CORRELATION_ID_HEADER,
                    event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        try {
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("Kafka did not acknowledge outbox event {} ({}); will retry", event.getEventId(), e.toString());
            return false;
        }
    }
}
