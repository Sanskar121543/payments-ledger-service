package io.github.sanskar121543.payments.common;

import io.github.sanskar121543.payments.idempotency.StoredResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business counters for Grafana; every metric also carries the app_instance tag. Write counters are
 * recorded by the controllers after the transaction has committed, so rolled-back work is never counted.
 */
@Component
public class LedgerMetrics {

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Registered up front so Grafana shows 0 rather than "No data" before the first occurrence.
        for (String scope : new String[] {"account", "idempotency_key"}) {
            registry.counter("ledger.lock.timeouts", "scope", scope);
        }
        for (String rule : new String[] {"large_amount", "high_velocity"}) {
            registry.counter("ledger.rules.hits", "rule", rule);
        }
        registry.counter("ledger.database.unavailable");
    }

    public void write(String operation, StoredResponse response) {
        String result;
        if (response.replayed()) {
            result = "replayed";
        } else {
            result = switch (response.status()) {
                case 201 -> "completed";
                case 409 -> "key_reused";
                case 404, 422 -> "rejected";
                default -> "other";
            };
        }
        registry.counter("ledger.writes", "operation", operation, "result", result).increment();
    }

    public void lockTimeout(String scope) {
        registry.counter("ledger.lock.timeouts", "scope", scope).increment();
    }

    public void databaseUnavailable() {
        registry.counter("ledger.database.unavailable").increment();
    }

    public void ruleHit(String rule) {
        registry.counter("ledger.rules.hits", "rule", rule).increment();
    }
}
