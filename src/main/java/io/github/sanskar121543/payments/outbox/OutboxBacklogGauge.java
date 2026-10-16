package io.github.sanskar121543.payments.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts unpublished outbox rows in the background. A gauge that queried the database during the
 * Prometheus scrape would make every metric of the instance disappear while the database is down.
 */
@Component
class OutboxBacklogGauge {

    private final OutboxRepository repository;
    private final AtomicLong unpublished = new AtomicLong();

    OutboxBacklogGauge(OutboxRepository repository, MeterRegistry meters) {
        this.repository = repository;
        Gauge.builder("ledger.outbox.unpublished", unpublished, AtomicLong::get)
                .description("Outbox events committed but not yet acknowledged by Kafka")
                .register(meters);
    }

    @Scheduled(fixedDelay = 5_000)
    void refresh() {
        try {
            unpublished.set(repository.countByPublishedAtIsNull());
        } catch (DataAccessException databaseUnavailable) {
            // Keep the last known value; the database's own health shows up elsewhere.
        }
    }
}
