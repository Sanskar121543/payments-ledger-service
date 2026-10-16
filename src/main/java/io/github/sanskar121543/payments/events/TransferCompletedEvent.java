package io.github.sanskar121543.payments.events;

import java.time.Instant;
import java.util.UUID;

/** Published once per completed transfer or reversal. Consumers deduplicate on {@code eventId}. */
public record TransferCompletedEvent(
        UUID eventId,
        long transferId,
        String kind,
        long fromAccountId,
        long toAccountId,
        long amountCents,
        String servedBy,
        String correlationId,
        Instant occurredAt) {

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String CORRELATION_ID_HEADER = "correlationId";

    /** Kafka key: every event from one sender lands in one partition, in order. */
    public String messageKey() {
        return String.valueOf(fromAccountId);
    }
}
