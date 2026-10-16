package io.github.sanskar121543.payments.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.Concurrently;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ConcurrencyIT {

    @Value("${local.server.port}")
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void hundredConcurrentTransfersConserveTheTotalAndNeverOverdraw() {
        long[] accounts = LongStream.range(0, 5).map(i -> api.fundedAccount("c" + i, 10_000)).toArray();
        Random random = new Random(42);
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
        assertThat(statuses.keySet()).as("only 201 COMPLETED or 422 FAILED, never 5xx: %s", statuses)
                .isSubsetOf(201, 422);
        long total = LongStream.of(accounts).map(api::balance).sum();
        assertThat(total).as("money is conserved inside the group").isEqualTo(50_000);
        assertThat(LongStream.of(accounts).map(api::balance)).allMatch(balance -> balance >= 0);
        assertInvariantsHold();
    }

    @Test
    void opposingTransfersBetweenTheSameAccountsDoNotDeadlock() {
        long a = api.fundedAccount("a", 100_000);
        long b = api.fundedAccount("b", 100_000);

        List<ApiResponse> responses = Concurrently.run(100,
                i -> i % 2 == 0 ? api.transfer(a, b, 100) : api.transfer(b, a, 100));

        assertThat(responses).extracting(ApiResponse::status).containsOnly(201);
        assertThat(api.balance(a)).isEqualTo(100_000);
        assertThat(api.balance(b)).isEqualTo(100_000);
        assertInvariantsHold();
    }

    @Test
    void drainingAnAccountConcurrentlyLetsExactlyTheAffordableTransfersThrough() {
        long source = api.fundedAccount("source", 5_000);
        long sink = api.createAccount("sink");

        List<ApiResponse> responses = Concurrently.run(10, i -> api.transfer(source, sink, 1_000));

        Map<Integer, Long> statuses = responses.stream()
                .collect(Collectors.groupingBy(ApiResponse::status, Collectors.counting()));
        assertThat(statuses).as("5 succeed, 5 are recorded as FAILED").isEqualTo(Map.of(201, 5L, 422, 5L));
        assertThat(api.balance(source)).isZero();
        assertThat(api.balance(sink)).isEqualTo(5_000);
        assertInvariantsHold();
    }

    private void assertInvariantsHold() {
        JsonNode report = api.invariants();
        assertThat(report.get("ok").asBoolean()).as(report.toString()).isTrue();
    }

    static <T> Map<T, Long> histogram(List<T> values) {
        return values.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
