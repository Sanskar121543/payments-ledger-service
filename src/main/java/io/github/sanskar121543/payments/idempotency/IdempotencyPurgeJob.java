package io.github.sanskar121543.payments.idempotency;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyRepository repository;
    private final Duration retention;

    public IdempotencyPurgeJob(IdempotencyRepository repository,
            @Value("${payments.idempotency.retention}") Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    // Safe on every instance at once: deleting rows that another instance already deleted is a no-op.
    @Scheduled(fixedDelayString = "${payments.idempotency.purge-interval}",
            initialDelayString = "${payments.idempotency.purge-interval}")
    @Transactional
    public int purge() {
        int deleted = repository.deleteCreatedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Purged {} idempotency keys older than {}", deleted, retention);
        }
        return deleted;
    }
}
