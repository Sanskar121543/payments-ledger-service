package io.github.sanskar121543.payments.outbox;

import io.github.sanskar121543.payments.events.TransferCompletedEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final String topic;

    public OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper, MeterRegistry meters,
            @Value("${payments.kafka.topic}") String topic) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
        Gauge.builder("ledger.outbox.unpublished", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events committed but not yet acknowledged by Kafka")
                .register(meters);
    }

    /** MANDATORY: the event must commit or roll back together with the ledger entries it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(TransferCompletedEvent event) {
        repository.save(new OutboxEvent(event.eventId(), topic, event.messageKey(),
                jsonMapper.writeValueAsString(event), event.correlationId()));
    }
}
