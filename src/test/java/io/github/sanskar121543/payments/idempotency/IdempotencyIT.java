package io.github.sanskar121543.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.Concurrently;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@IntegrationTest
class IdempotencyIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    DataSource dataSource;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = ApiClient.forPort(port);
    }

    @Test
    void sameKeyRetried10TimesCreatesExactlyOneTransfer() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        List<ApiResponse> attempts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            attempts.add(api.transfer(alice, bob, 1_000, key));
        }

        assertThat(attempts).extracting(ApiResponse::status).containsOnly(201);
        assertThat(attempts).extracting(ApiResponse::rawBody).containsOnly(attempts.getFirst().rawBody());
        assertThat(attempts.getFirst().header(StoredResponse.REPLAYED_HEADER)).isNull();
        assertThat(attempts.subList(1, 10)).allMatch(r -> "true".equals(r.header(StoredResponse.REPLAYED_HEADER)));
        assertThat(api.balance(alice)).isEqualTo(9_000);
        assertThat(api.balance(bob)).isEqualTo(1_000);
        assertThat(api.get("/v1/accounts/" + bob + "/entries").body()).hasSize(1);
    }

    @Test
    void sameKeySentConcurrentlyCreatesExactlyOneTransfer() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        List<ApiResponse> attempts = Concurrently.run(10, i -> api.transfer(alice, bob, 1_000, key));

        assertThat(attempts).extracting(ApiResponse::status).containsOnly(201);
        assertThat(attempts).extracting(r -> r.longField("id")).containsOnly(attempts.getFirst().longField("id"));
        assertThat(api.balance(bob)).isEqualTo(1_000);
    }

    @Test
    void sameKeyWithADifferentRequestIs409AndChangesNothing() {
        long alice = api.fundedAccount("alice", 10_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();
        api.transfer(alice, bob, 1_000, key);

        ApiResponse reused = api.transfer(alice, bob, 2_000, key);

        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.problemType()).isEqualTo("/problems/idempotency-key-reused");
        assertThat(api.balance(bob)).isEqualTo(1_000);
    }

    @Test
    void businessFailuresAreStoredAndReplayedEvenAfterTheAccountIsFunded() {
        long alice = api.fundedAccount("alice", 500);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        ApiResponse first = api.transfer(alice, bob, 1_000, key);
        api.transfer(ApiClient.FUNDING_ACCOUNT_ID, alice, 5_000);
        ApiResponse retry = api.transfer(alice, bob, 1_000, key);

        assertThat(first.status()).isEqualTo(422);
        assertThat(retry.status()).isEqualTo(422);
        assertThat(retry.rawBody()).isEqualTo(first.rawBody());
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void rejectedRequestsAreNotStoredSoTheKeyCanBeReusedAfterFixingTheRequest() {
        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        ApiResponse invalid = api.transfer(alice, bob, 0, key);
        ApiResponse fixed = api.transfer(alice, bob, 100, key);

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(fixed.status()).isEqualTo(201);
    }

    @Test
    void keysAreScopedPerApiClient() {
        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        ApiResponse fromTest = api.transfer(alice, bob, 100, key);
        ApiResponse fromOther = api.withKey(ApiClient.OTHER_CLIENT_KEY).transfer(alice, bob, 100, key);

        assertThat(fromTest.status()).isEqualTo(201);
        assertThat(fromOther.status()).isEqualTo(201);
        assertThat(fromOther.longField("id")).isNotEqualTo(fromTest.longField("id"));
        assertThat(api.balance(bob)).isEqualTo(200);
    }

    @Test
    void aKeyStillHeldByAnUnfinishedRequestReturns409AfterTheLockTimeout() throws Exception {
        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        String key = UUID.randomUUID().toString();

        try (Connection inFlight = dataSource.getConnection()) {
            inFlight.setAutoCommit(false);
            try (PreparedStatement claim = inFlight.prepareStatement(
                    "INSERT INTO idempotency_keys (client_id, idem_key, request_hash) VALUES ('test', ?, 'x')")) {
                claim.setString(1, key);
                claim.executeUpdate();
            }

            ApiResponse blocked = api.transfer(alice, bob, 100, key);

            assertThat(blocked.status()).isEqualTo(409);
            assertThat(blocked.problemType()).isEqualTo("/problems/idempotency-key-in-use");
            assertThat(blocked.header("Retry-After")).isEqualTo("1");
            inFlight.rollback();
        }

        assertThat(api.transfer(alice, bob, 100, key).status()).as("the key is free again after rollback")
                .isEqualTo(201);
    }

    @Test
    void accountCreationIsIdempotentToo() {
        String key = UUID.randomUUID().toString();

        ApiResponse first = api.post("/v1/accounts", Map.of("name", "carol"), key);
        ApiResponse second = api.post("/v1/accounts", Map.of("name", "carol"), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.longField("id")).isEqualTo(first.longField("id"));
        assertThat(second.header(StoredResponse.REPLAYED_HEADER)).isEqualTo("true");
    }
}
