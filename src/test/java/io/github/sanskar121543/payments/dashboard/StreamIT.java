package io.github.sanskar121543.payments.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

@IntegrationTest
class StreamIT {

    @Value("${local.server.port}")
    int port;

    @Test
    void theStreamDeliversNewTransfersAsServerSentEvents() throws Exception {
        ApiClient api = ApiClient.forPort(port);
        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/stream"))
                        .header("X-API-Key", ApiClient.TEST_KEY).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(stream.headers().firstValue("X-Served-By")).contains("test");

        long alice = api.fundedAccount("alice", 5_000);
        long bob = api.createAccount("bob");
        long transferId = api.transfer(alice, bob, 4_321).longField("id");

        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String next;
                while ((next = reader.readLine()) != null) {
                    if (next.startsWith("data:") && next.contains("\"transferId\":" + transferId + ",")) {
                        return next;
                    }
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        String event = line.get(30, TimeUnit.SECONDS);
        assertThat(event).contains("\"amountCents\":4321");
    }

    @Test
    void theStreamNeedsAnApiKey() throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/stream")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
