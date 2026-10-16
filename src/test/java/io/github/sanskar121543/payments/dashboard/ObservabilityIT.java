package io.github.sanskar121543.payments.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

@IntegrationTest
class ObservabilityIT {

    @Value("${local.server.port}")
    int port;

    @Test
    void prometheusEndpointExposesBusinessMetricsTaggedWithTheInstance() throws Exception {
        ApiClient api = ApiClient.forPort(port);
        long alice = api.fundedAccount("alice", 1_000);
        ApiResponse failed = api.transfer(alice, api.createAccount("bob"), 5_000);
        assertThat(failed.status()).isEqualTo(422);

        String metrics = get("/actuator/prometheus");

        assertThat(metrics).contains("ledger_writes_total{app_instance=\"test\",operation=\"transfer\",result=\"completed\"}");
        assertThat(metrics).contains("ledger_writes_total{app_instance=\"test\",operation=\"transfer\",result=\"rejected\"}");
        assertThat(metrics).contains("http_server_requests_seconds_bucket");
        assertThat(metrics).contains("hikaricp_connections_active");
    }

    @Test
    void dashboardPageIsServedWithoutAKey() throws Exception {
        String page = get("/");

        assertThat(page).contains("<title>Payments Ledger</title>").contains("vendor/chart.js/4.5.1/chart.umd.min.js");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
