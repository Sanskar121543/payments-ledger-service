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
    private final Duration maxBatchDuration;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, SingletonJobLock jobLock,
            @Value("${payments.outbox.batch-size}") int batchSize,
            @Value("${payments.outbox.send-timeout}") Duration sendTimeout,
            @Value("${payments.outbox.max-batch-duration}") Duration maxBatchDuration) {
        this.repository = repository;
        this.kafka = kafka;
        this.jobLock = jobLock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.maxBatchDuration = maxBatchDuration;
    }

    /**
     * TODO(practice) rules:
     * - One relay at a time across all instances: jobLock.tryAcquire(SingletonJobLock.OUTBOX_RELAY), else return 0.
     * - Up to batchSize unpublished rows, oldest first (repository.findByPublishedAtIsNullOrderByIdAsc).
     * - send(event) them in order and stop at the first one Kafka does not acknowledge, so a later event never
     *   overtakes an earlier one.
     * - Mark each acknowledged row published and flush it at once, not at commit. Why? Look up
     *   idle_in_transaction_session_timeout in application.yaml.
     * - Stop starting new sends once maxBatchDuration has passed.
     * - Return how many rows were published.
     */
    @Scheduled(fixedDelayString = "${payments.outbox.poll-interval}")
    @Transactional
    public int relayBatch() {
        throw new UnsupportedOperationException("practice: write OutboxRelay.relayBatch");
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
