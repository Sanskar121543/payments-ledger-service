package io.github.sanskar121543.payments.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.Concurrently;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The proof test: 100 concurrent transfers through nginx, spread over three app instances. */
class MultiInstanceConcurrencyIT {

    static LedgerTopology topology;
    static ApiClient api;

    @BeforeAll
    static void start() {
        topology = LedgerTopology.start(List.of("app1", "app2", "app3"), true);
        api = topology.viaNginx();
    }

    @AfterAll
    static void stop() {
        topology.close();
    }

    @Test
    void hundredConcurrentTransfersAcrossThreeInstancesConserveMoneyAndNeverOverdraw() {
        long[] accounts = LongStream.range(0, 5).map(i -> api.fundedAccount("c" + i, 10_000)).toArray();
        Random random = new Random(7);
        long[][] plan = new long[100][];
        for (int i = 0; i < plan.length; i++) {
            int from = random.nextInt(accounts.length);
            int to = (from + 1 + random.nextInt(accounts.length - 1)) % accounts.length;
            plan[i] = new long[] {accounts[from], accounts[to], 100 + random.nextInt(4_900)};
        }

        List<ApiResponse> responses = Concurrently.run(plan.length,
                i -> api.transfer(plan[i][0], plan[i][1], plan[i][2]));

        Map<Integer, Long> statuses = responses.stream()
                .collect(Collectors.groupingBy(ApiResponse::status, Collectors.counting()));
        Set<String> instances = responses.stream().map(r -> r.header("X-Served-By")).collect(Collectors.toSet());
        assertThat(statuses.keySet()).as("statuses %s", statuses).isSubsetOf(201, 422);
        assertThat(instances).as("nginx spread the requests over every instance")
                .containsExactlyInAnyOrder("app1", "app2", "app3");
        assertThat(LongStream.of(accounts).map(api::balance).sum()).isEqualTo(50_000);
        assertThat(LongStream.of(accounts).map(api::balance)).allMatch(balance -> balance >= 0);
        JsonNode report = api.invariants();
        assertThat(report.get("ok").asBoolean()).as(report.toString()).isTrue();
    }

    @Test
    void nginxHidesManagementEndpointsAndRejectsPathTricks() {
        ApiClient noKey = api.withKey(null);

        assertThat(noKey.get("/actuator/health").status()).isEqualTo(200);
        assertThat(noKey.get("/actuator/prometheus").status()).isEqualTo(404);
        assertThat(noKey.get("/actuator").status()).isEqualTo(404);
        assertThat(noKey.get("/%61ctuator/prometheus").status()).isEqualTo(404);
        assertThat(noKey.get("/actuator;x/prometheus").status()).isEqualTo(400);
        assertThat(noKey.get("/v1;x/accounts").status()).isEqualTo(400);
        assertThat(noKey.get("/%761/accounts").status()).isEqualTo(401);
    }

    @Test
    void oneKeySentConcurrentlyToDifferentInstancesStillMakesOneTransfer() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        List<ApiResponse> responses = Concurrently.run(10, i -> api.transfer(alice, bob, 1_000, key));

        assertThat(responses).extracting(ApiResponse::status).containsOnly(201);
        assertThat(responses).extracting(r -> r.longField("id")).containsOnly(responses.getFirst().longField("id"));
        assertThat(api.balance(bob)).isEqualTo(1_000);
    }
}
