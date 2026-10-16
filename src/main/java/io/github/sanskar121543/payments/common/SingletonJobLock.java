package io.github.sanskar121543.payments.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets a scheduled job run on only one instance at a time. The Postgres advisory lock is tied to the
 * current transaction, so it is released at commit/rollback, or when a crashed instance's connection
 * goes away, and another instance takes over on its next tick.
 */
@Component
public class SingletonJobLock {

    // The two-int form lives in a different key space from Flyway's single-bigint migration lock.
    private static final int NAMESPACE = 0x4C454447;

    public static final int OUTBOX_RELAY = 1;
    public static final int IDEMPOTENCY_PURGE = 2;

    private final JdbcTemplate jdbc;

    public SingletonJobLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryAcquire(int job) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class, NAMESPACE, job));
    }
}
