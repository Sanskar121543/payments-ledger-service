package io.github.sanskar121543.payments.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AccountApiIT {

    @Value("${local.server.port}")
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void createsAndReadsACustomerAccountWithZeroBalance() {
        ApiResponse created = api.post("/v1/accounts", Map.of("name", "alice"), UUID.randomUUID().toString());

        assertThat(created.status()).isEqualTo(201);
        long id = created.longField("id");

        ApiResponse read = api.get("/v1/accounts/" + id);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.textField("name")).isEqualTo("alice");
        assertThat(read.textField("type")).isEqualTo("CUSTOMER");
        assertThat(read.textField("currency")).isEqualTo("USD");
        assertThat(read.longField("balanceCents")).isZero();
    }

    @Test
    void listsAccountsStartingWithTheFundingAccount() {
        ApiResponse list = api.get("/v1/accounts");

        assertThat(list.status()).isEqualTo(200);
        JsonNode first = list.body().get(0);
        assertThat(first.get("id").asLong()).isEqualTo(ApiClient.FUNDING_ACCOUNT_ID);
        assertThat(first.get("type").asString()).isEqualTo("FUNDING");
    }

    @Test
    void rejectsRequestsWithoutAValidApiKey() {
        ApiResponse noKey = api.withKey(null).get("/v1/accounts");
        ApiResponse wrongKey = api.withKey("not-a-key").get("/v1/accounts");

        assertThat(noKey.status()).isEqualTo(401);
        assertThat(noKey.problemType()).isEqualTo("/problems/unauthorized");
        assertThat(wrongKey.status()).isEqualTo(401);
    }

    @Test
    void healthIsOpenWithoutAKey() {
        assertThat(api.withKey(null).get("/actuator/health").status()).isEqualTo(200);
    }

    @Test
    void rejectsBlankNames() {
        ApiResponse response = api.post("/v1/accounts", Map.of("name", " "), UUID.randomUUID().toString());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.problemType()).isEqualTo("/problems/validation");
    }

    @Test
    void unknownAccountIs404() {
        ApiResponse response = api.get("/v1/accounts/999999999");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.problemType()).isEqualTo("/problems/account-not-found");
    }

    @Test
    void entriesShowEveryCentThatMovedNewestFirst() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        api.transfer(alice, bob, 2_500);

        JsonNode entries = api.get("/v1/accounts/" + alice + "/entries").body();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("amountCents").asLong()).isEqualTo(-2_500);
        assertThat(entries.get(0).get("direction").asString()).isEqualTo("DEBIT");
        assertThat(entries.get(1).get("amountCents").asLong()).isEqualTo(10_000);
        assertThat(entries.get(1).get("direction").asString()).isEqualTo("CREDIT");
    }
}
