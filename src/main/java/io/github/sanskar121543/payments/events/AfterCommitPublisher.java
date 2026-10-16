package io.github.sanskar121543.payments.events;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publish-after-commit: runs only once the transfer's transaction has committed. If the process dies
 * between that commit and Kafka acknowledging the record, the event is lost; the money is still correct.
 */
@Component
class AfterCommitPublisher {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final String topic;

    AfterCommitPublisher(KafkaTemplate<String, String> kafka, JsonMapper jsonMapper,
            @Value("${payments.kafka.topic}") String topic) {
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void publish(TransferCompletedEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.messageKey(),
                    jsonMapper.writeValueAsString(event));
            record.headers().add(TransferCompletedEvent.EVENT_ID_HEADER,
                    event.eventId().toString().getBytes(StandardCharsets.UTF_8));
            if (event.correlationId() != null) {
                record.headers().add(TransferCompletedEvent.CORRELATION_ID_HEADER,
                        event.correlationId().getBytes(StandardCharsets.UTF_8));
            }
            kafka.send(record).whenComplete((result, error) -> {
                if (error != null) {
                    log.error("transfer.completed for transfer {} was lost: {}", event.transferId(), error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.error("transfer.completed for transfer {} was lost: {}", event.transferId(), e.getMessage());
        }
    }
}
