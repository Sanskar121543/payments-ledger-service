package io.github.sanskar121543.payments.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class DbTime {

    private DbTime() {
    }

    /**
     * Now, at the precision Postgres stores (microseconds). The JVM clock is finer, so an entity serialised
     * right after insert (a 201 body, which is also stored for idempotent replays) would otherwise show a
     * different createdAt than a later GET of the same row.
     */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
