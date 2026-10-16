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
        Instant checkedAt) {

    static InvariantReport of(long sum, long mismatches, long unbalanced, long negative, long accounts,
            long transfers, long entries) {
        boolean ok = sum == 0 && mismatches == 0 && unbalanced == 0 && negative == 0;
        return new InvariantReport(ok, sum, mismatches, unbalanced, negative, accounts, transfers, entries,
                Instant.now());
    }
}
