package io.github.sanskar121543.payments.outbox;

import io.github.sanskar121543.payments.events.TransferCompletedEvent;
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

    public OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper,
            @Value("${payments.kafka.topic}") String topic) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    /** MANDATORY: the event must commit or roll back together with the ledger entries it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(TransferCompletedEvent event) {
        repository.save(new OutboxEvent(event.eventId(), topic, event.messageKey(),
                jsonMapper.writeValueAsString(event), event.correlationId()));
    }
}
