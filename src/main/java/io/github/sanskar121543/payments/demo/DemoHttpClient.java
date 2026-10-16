package io.github.sanskar121543.payments.demo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A plain HTTP client for the demo buttons: they call the public API exactly like any other client. */
final class DemoHttpClient {

    record Reply(int status, JsonNode body, String servedBy, boolean replayed, long latencyMillis) {

        long longField(String name) {
            return body.get(name).asLong();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String apiKey;
    private final JsonMapper jsonMapper;

    DemoHttpClient(String baseUrl, String apiKey, JsonMapper jsonMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.jsonMapper = jsonMapper;
    }

    Reply post(String path, Map<String, Object> body, String idempotencyKey) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();
        return send(request);
    }

    Reply get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-API-Key", apiKey)
                .GET()
                .build());
    }

    private Reply send(HttpRequest request) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
            JsonNode body = response.body().isBlank() ? null : jsonMapper.readTree(response.body());
            return new Reply(response.statusCode(), body, response.headers().firstValue("X-Served-By").orElse("?"),
                    response.headers().firstValue("Idempotent-Replayed").isPresent(), latency);
        } catch (IOException e) {
            throw new IllegalStateException("Demo request to " + request.uri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
