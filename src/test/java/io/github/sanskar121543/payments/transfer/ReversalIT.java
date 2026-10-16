package io.github.sanskar121543.payments.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.Concurrently;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ReversalIT {

    @Value("${local.server.port}")
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void reversalSendsTheMoneyBackWithTwoNewOppositeEntries() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        long original = api.transfer(alice, bob, 3_000).longField("id");

        ApiResponse reversal = reverse(original);

        assertThat(reversal.status()).isEqualTo(201);
        assertThat(reversal.textField("kind")).isEqualTo("REVERSAL");
        assertThat(reversal.longField("reversesTransfer")).isEqualTo(original);
        assertThat(reversal.longField("fromAccount")).isEqualTo(bob);
        assertThat(reversal.longField("toAccount")).isEqualTo(alice);
        JsonNode entries = reversal.body().get("entries");
        assertThat(entries.get(0).get("amountCents").asLong()).isEqualTo(-3_000);
        assertThat(entries.get(1).get("amountCents").asLong()).isEqualTo(3_000);
        assertThat(api.balance(alice)).isEqualTo(10_000);
        assertThat(api.balance(bob)).isZero();
        ApiResponse originalNow = api.get("/v1/transfers/" + original);
        assertThat(originalNow.textField("status")).as("the original is never modified").isEqualTo("COMPLETED");
        assertThat(originalNow.longField("reversedBy")).isEqualTo(reversal.longField("id"));
    }

    @Test
    void aTransferCanBeReversedOnlyOnce() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        long original = api.transfer(alice, bob, 3_000).longField("id");
        reverse(original);

        ApiResponse second = reverse(original);

        assertThat(second.status()).isEqualTo(422);
        assertThat(second.problemType()).isEqualTo("/problems/already-reversed");
        assertThat(api.balance(alice)).isEqualTo(10_000);
    }

    @Test
    void concurrentReversalsOfOneTransferProduceExactlyOneReversal() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        long original = api.transfer(alice, bob, 3_000).longField("id");

        List<ApiResponse> attempts = Concurrently.run(10, i -> reverse(original));

        Map<Integer, Long> statuses = attempts.stream()
                .collect(Collectors.groupingBy(ApiResponse::status, Collectors.counting()));
        assertThat(statuses).isEqualTo(Map.of(201, 1L, 422, 9L));
        assertThat(api.balance(alice)).isEqualTo(10_000);
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void reversalFailsWithoutOverdraftIfTheRecipientAlreadySpentTheMoney() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        long carol = api.createAccount("carol");
        long original = api.transfer(alice, bob, 3_000).longField("id");
        api.transfer(bob, carol, 2_000);

        ApiResponse failed = reverse(original);

        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.problemType()).isEqualTo("/problems/insufficient-funds");
        assertThat(api.balance(bob)).isEqualTo(1_000);

        api.transfer(carol, bob, 2_000);
        assertThat(reverse(original).status()).as("a later reversal attempt can succeed").isEqualTo(201);
        assertThat(api.balance(alice)).isEqualTo(10_000);
    }

    @Test
    void failedTransfersAndReversalsCannotBeReversed() {
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");
        long failed = api.transfer(alice, bob, 5_000).longField("transferId");
        long completed = api.transfer(alice, bob, 500).longField("id");
        long reversal = reverse(completed).longField("id");

        assertThat(reverse(failed).problemType()).isEqualTo("/problems/not-reversible");
        assertThat(reverse(reversal).problemType()).isEqualTo("/problems/not-reversible");
    }

    @Test
    void reversingAnUnknownTransferIs404() {
        ApiResponse response = reverse(999_999_999L);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.problemType()).isEqualTo("/problems/transfer-not-found");
    }

    private ApiResponse reverse(long transferId) {
        return api.post("/v1/transfers/" + transferId + "/reversals", Map.of(), UUID.randomUUID().toString());
    }
}
