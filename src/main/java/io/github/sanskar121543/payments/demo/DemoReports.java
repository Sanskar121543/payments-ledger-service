package io.github.sanskar121543.payments.demo;

import io.github.sanskar121543.payments.ledger.InvariantReport;
import java.util.List;
import java.util.Map;

final class DemoReports {

    private DemoReports() {
    }

    record Balance(long accountId, String name, long beforeCents, long afterCents) {
    }

    record Attempt(int status, Long transferId, boolean replayed, String servedBy, long latencyMillis) {
    }

    record RetryReport(
            String idempotencyKey,
            int requests,
            List<Attempt> attempts,
            long distinctTransfers,
            Map<String, Long> byInstance,
            List<Balance> balances,
            InvariantReport invariants) {
    }

    record ConcurrentReport(
            int requests,
            Map<Integer, Long> byStatus,
            Map<String, Long> byInstance,
            List<Balance> balances,
            long totalBeforeCents,
            long totalAfterCents,
            boolean totalConserved,
            boolean noNegativeBalances,
            long p50Millis,
            long p95Millis,
            long durationMillis,
            InvariantReport invariants) {
    }
}
