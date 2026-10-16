package io.github.sanskar121543.payments.activity;

import io.github.sanskar121543.payments.events.TransferCompletedEvent;
import io.github.sanskar121543.payments.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class ActivityConsumer {

    private static final Logger log = LoggerFactory.getLogger(ActivityConsumer.class);

    private final ActivityService activity;
    private final JsonMapper jsonMapper;

    ActivityConsumer(ActivityService activity, JsonMapper jsonMapper) {
        this.activity = activity;
        this.jsonMapper = jsonMapper;
    }

    // The offset is committed only after this method returns, so a crash here means redelivery, never loss.
    @KafkaListener(topics = "${payments.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
    void onTransferCompleted(ConsumerRecord<String, String> record) {
        Header correlation = record.headers().lastHeader(TransferCompletedEvent.CORRELATION_ID_HEADER);
        if (correlation != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, new String(correlation.value(), StandardCharsets.UTF_8));
        }
        try {
            TransferCompletedEvent event = jsonMapper.readValue(record.value(), TransferCompletedEvent.class);
            if (!activity.record(event)) {
                log.info("Duplicate delivery of event {} ignored", event.eventId());
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
