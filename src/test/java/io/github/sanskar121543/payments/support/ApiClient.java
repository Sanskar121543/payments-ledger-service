package io.github.sanskar121543.payments.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class ApiClient {

    public static final String TEST_KEY = "test-key";
    public static final String OTHER_CLIENT_KEY = "other-key";
    public static final long FUNDING_ACCOUNT_ID = 1L;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String apiKey;

    public ApiClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public static ApiClient forPort(int port) {
        return new ApiClient("http://localhost:" + port, TEST_KEY);
    }

    public ApiClient withKey(String key) {
        return new ApiClient(baseUrl, key);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ApiResponse get(String path) {
        return send(request(path).GET().build());
    }

    public ApiResponse post(String path, Object body, String idempotencyKey) {
        return postRaw(path, JSON.writeValueAsString(body), idempotencyKey);
    }

    public ApiResponse postRaw(String path, String json, String idempotencyKey) {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder.build());
    }

    public ApiResponse transfer(long from, long to, long amountCents, String idempotencyKey) {
        return post("/v1/transfers", transferBody(from, to, amountCents), idempotencyKey);
    }

    public ApiResponse transfer(long from, long to, long amountCents) {
        return transfer(from, to, amountCents, UUID.randomUUID().toString());
    }

    public long createAccount(String name) {
        ApiResponse response = post("/v1/accounts", Map.of("name", name), UUID.randomUUID().toString());
        if (response.status() != 201) {
            throw new IllegalStateException("Could not create account: " + response.status() + " " + response.rawBody());
        }
        return response.longField("id");
    }

    public long fundedAccount(String name, long cents) {
        long id = createAccount(name);
        if (cents > 0) {
            ApiResponse funding = transfer(FUNDING_ACCOUNT_ID, id, cents);
            if (funding.status() != 201) {
                throw new IllegalStateException("Could not fund account: " + funding.status() + " " + funding.rawBody());
            }
        }
        return id;
    }

    public long balance(long accountId) {
        return get("/v1/accounts/" + accountId).longField("balanceCents");
    }

    public JsonNode invariants() {
        return get("/v1/ledger/invariants").body();
    }

    public static Map<String, Object> transferBody(long from, long to, long amountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccount", from);
        body.put("toAccount", to);
        body.put("amountCents", amountCents);
        return body;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return builder;
    }

    private ApiResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode body = parseJson(raw);
            return new ApiResponse(response.statusCode(), response.headers(), raw, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // nginx's own error pages are HTML; only JSON bodies are parsed.
    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (JacksonException notJson) {
            return null;
        }
    }

    public static class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException cause) {
            super(cause);
        }
    }
}
