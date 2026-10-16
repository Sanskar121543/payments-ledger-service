package io.github.sanskar121543.payments.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class TransferIT {

    @Value("${local.server.port}")
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void completedTransferMovesMoneyAndWritesTwoEntriesThatSumToZero() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");

        ApiResponse response = api.transfer(alice, bob, 2_500);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.textField("status")).isEqualTo("COMPLETED");
        JsonNode entries = response.body().get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("accountId").asLong()).isEqualTo(alice);
        assertThat(entries.get(0).get("amountCents").asLong()).isEqualTo(-2_500);
        assertThat(entries.get(1).get("accountId").asLong()).isEqualTo(bob);
        assertThat(entries.get(1).get("amountCents").asLong()).isEqualTo(2_500);
        assertThat(api.balance(alice)).isEqualTo(7_500);
        assertThat(api.balance(bob)).isEqualTo(2_500);
    }

    @Test
    void insufficientFundsIsRecordedAsFailedWithNoEntries() {
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");

        ApiResponse response = api.transfer(alice, bob, 1_001);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.problemType()).isEqualTo("/problems/insufficient-funds");
        assertThat(response.body().get("status").asInt()).as("RFC 9457: status is the HTTP code").isEqualTo(422);
        assertThat(response.textField("transferStatus")).isEqualTo("FAILED");
        assertThat(response.rawBody().indexOf("\"status\"")).as("no duplicate status member")
                .isEqualTo(response.rawBody().lastIndexOf("\"status\""));
        long transferId = response.longField("transferId");
        ApiResponse stored = api.get("/v1/transfers/" + transferId);
        assertThat(stored.textField("status")).isEqualTo("FAILED");
        assertThat(stored.textField("failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(stored.body().get("entries")).isEmpty();
        assertThat(api.balance(alice)).isEqualTo(1_000);
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void fundingAccountIsTheOnlyAccountAllowedBelowZero() {
        long before = api.balance(ApiClient.FUNDING_ACCOUNT_ID);
        long alice = api.fundedAccount("alice", 5_000);

        assertThat(api.balance(ApiClient.FUNDING_ACCOUNT_ID)).isEqualTo(before - 5_000);
        assertThat(api.balance(alice)).isEqualTo(5_000);
    }

    @Test
    void selfTransferIsRejectedBeforeAnythingIsStored() {
        long alice = api.fundedAccount("alice", 1_000);

        ApiResponse response = api.transfer(alice, alice, 100);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.problemType()).as(response.rawBody()).isEqualTo("/problems/validation");
    }

    @Test
    void unknownAccountIs422() {
        long alice = api.fundedAccount("alice", 1_000);

        ApiResponse response = api.transfer(alice, 999_999_999L, 100);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.problemType()).isEqualTo("/problems/account-not-found");
    }

    @Test
    void decimalAmountsAreRejectedInsteadOfSilentlyTruncated() {
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");

        ApiResponse response = api.postRaw("/v1/transfers",
                "{\"fromAccount\":" + alice + ",\"toAccount\":" + bob + ",\"amountCents\":10.99}",
                UUID.randomUUID().toString());

        assertThat(response.status()).isEqualTo(400);
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void unknownFieldsAreRejected() {
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");

        ApiResponse response = api.postRaw("/v1/transfers",
                "{\"fromAccount\":" + alice + ",\"toAccount\":" + bob + ",\"amountCents\":10,\"currency\":\"EUR\"}",
                UUID.randomUUID().toString());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.problemType()).isEqualTo("/problems/malformed-request");
    }

    @Test
    void nonPositiveAndOversizedAmountsAreRejected() {
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");

        assertThat(api.transfer(alice, bob, 0).status()).isEqualTo(400);
        assertThat(api.transfer(alice, bob, -5).status()).isEqualTo(400);
        assertThat(api.transfer(alice, bob, TransferRequest.MAX_AMOUNT_CENTS + 1).status()).isEqualTo(400);
    }

    @Test
    void idempotencyKeyHeaderIsRequired() {
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");

        ApiResponse response = api.post("/v1/transfers", ApiClient.transferBody(alice, bob, 100), null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void responsesCarryACorrelationIdThatIsStoredOnTheTransfer() {
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");

        ApiResponse response = api.transfer(alice, bob, 100);

        String correlationId = response.header("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(response.textField("correlationId")).isEqualTo(correlationId);
    }
}
