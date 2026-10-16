package io.github.sanskar121543.payments.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class InvariantIT {

    @Value("${local.server.port}")
    int port;

    @Test
    void ledgerSumsToZeroAndCachedBalancesMatchAfterMixedActivity() {
        ApiClient api = ApiClient.forPort(port);
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.fundedAccount("bob", 3_000);
        api.transfer(alice, bob, 4_000);
        api.transfer(bob, alice, 6_500);
        api.transfer(bob, alice, 1_000_000);

        JsonNode report = api.invariants();

        assertThat(report.get("ok").asBoolean()).isTrue();
        assertThat(report.get("sumOfAllEntriesCents").asLong()).isZero();
        assertThat(report.get("accountsWithCachedBalanceMismatch").asLong()).isZero();
        assertThat(report.get("transfersWithUnbalancedEntries").asLong()).isZero();
        assertThat(report.get("customerAccountsBelowZero").asLong()).isZero();
        assertThat(api.balance(alice)).isEqualTo(12_500);
        assertThat(api.balance(bob)).isEqualTo(500);
    }
}
