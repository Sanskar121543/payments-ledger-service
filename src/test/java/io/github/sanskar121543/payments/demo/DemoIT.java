package io.github.sanskar121543.payments.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class DemoIT {

    @Value("${local.server.port}")
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void sameRequestTenTimesCreatesOneTransferAndNineReplays() {
        ApiResponse response = api.post("/v1/demo/retry-same-request", Map.of(), null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode report = response.body();
        assertThat(report.get("distinctTransfers").asLong()).isEqualTo(1);
        JsonNode attempts = report.get("attempts");
        assertThat(attempts).hasSize(10);
        assertThat(StreamSupport.stream(attempts.spliterator(), false))
                .allMatch(a -> a.get("status").asInt() == 201);
        assertThat(StreamSupport.stream(attempts.spliterator(), false).filter(a -> a.get("replayed").asBoolean()))
                .hasSize(9);
        JsonNode balances = report.get("balances");
        assertThat(balances.get(0).get("afterCents").asLong()).isEqualTo(9_000);
        assertThat(balances.get(1).get("afterCents").asLong()).isEqualTo(1_000);
        assertThat(report.get("invariants").get("ok").asBoolean()).isTrue();
    }

    @Test
    void hundredConcurrentTransfersConserveTheTotal() {
        ApiResponse response = api.post("/v1/demo/concurrent-transfers", Map.of(), null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode report = response.body();
        assertThat(report.get("requests").asInt()).isEqualTo(100);
        assertThat(report.get("totalConserved").asBoolean()).isTrue();
        assertThat(report.get("noNegativeBalances").asBoolean()).isTrue();
        assertThat(report.get("totalBeforeCents").asLong()).isEqualTo(50_000);
        assertThat(report.get("byStatus").properties().stream().map(java.util.Map.Entry::getKey).toList())
                .isSubsetOf("201", "422");
        assertThat(report.get("invariants").get("ok").asBoolean()).isTrue();
    }

    @Test
    void demosNeedAnApiKey() {
        assertThat(api.withKey(null).post("/v1/demo/concurrent-transfers", Map.of(), null).status()).isEqualTo(401);
    }
}
