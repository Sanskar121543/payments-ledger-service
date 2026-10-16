package io.github.sanskar121543.payments.ledger;

import java.time.Instant;

public record InvariantReport(
        boolean ok,
        long sumOfAllEntriesCents,
        long accountsWithCachedBalanceMismatch,
        long transfersWithUnbalancedEntries,
        long customerAccountsBelowZero,
        long accounts,
        long transfers,
        long entries,
        long outboxUnpublished,
        Instant checkedAt) {

    /** outboxUnpublished is informational: events waiting for Kafka do not make the ledger wrong. */
    static InvariantReport of(long sum, long mismatches, long unbalanced, long negative, long accounts,
            long transfers, long entries, long outboxUnpublished) {
        boolean ok = sum == 0 && mismatches == 0 && unbalanced == 0 && negative == 0;
        return new InvariantReport(ok, sum, mismatches, unbalanced, negative, accounts, transfers, entries,
                outboxUnpublished, Instant.now());
    }
}
