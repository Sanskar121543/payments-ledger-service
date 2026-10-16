package io.github.sanskar121543.payments.activity;

import java.time.Instant;
import java.util.UUID;

public record ActivityItem(
        long id,
        UUID eventId,
        long transferId,
        String kind,
        long fromAccount,
        long toAccount,
        long amountCents,
        String servedBy,
        String consumedBy,
        String correlationId,
        Instant occurredAt,
        Instant receivedAt,
        boolean largeAmount,
        boolean highVelocity) {
}
