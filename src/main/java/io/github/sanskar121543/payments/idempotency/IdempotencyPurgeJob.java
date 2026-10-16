package io.github.sanskar121543.payments.idempotency;

import io.github.sanskar121543.payments.common.SingletonJobLock;
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
    private final SingletonJobLock jobLock;
    private final Duration retention;

    public IdempotencyPurgeJob(IdempotencyRepository repository, SingletonJobLock jobLock,
            @Value("${payments.idempotency.retention}") Duration retention) {
        this.repository = repository;
        this.jobLock = jobLock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${payments.idempotency.purge-interval}",
            initialDelayString = "${payments.idempotency.purge-interval}")
    @Transactional
    public int purge() {
        if (!jobLock.tryAcquire(SingletonJobLock.IDEMPOTENCY_PURGE)) {
            return 0;
        }
        int deleted = repository.deleteCreatedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Purged {} idempotency keys older than {}", deleted, retention);
        }
        return deleted;
    }
}
