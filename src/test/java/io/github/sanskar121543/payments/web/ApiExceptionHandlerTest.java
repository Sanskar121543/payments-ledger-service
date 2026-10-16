package io.github.sanskar121543.payments.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.common.LedgerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

class ApiExceptionHandlerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ApiExceptionHandler handler = new ApiExceptionHandler(new LedgerMetrics(meters));
    private final ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(ApiExceptionHandler.class);

    @Test
    void poolTimeoutsAndLostConnectionsAreNotReportedAsInternalErrors() {
        // Pool exhausted: JpaTransactionManager cannot begin. Connection lost mid-request: Spring JDBC translation.
        assertThat(resolver.resolveMethod(new CannotCreateTransactionException("Connection is not available")).getName())
                .isEqualTo("databaseUnavailable");
        assertThat(resolver.resolveMethod(new CannotGetJdbcConnectionException("refused")).getName())
                .isEqualTo("databaseUnavailable");
        assertThat(resolver.resolveMethod(new CannotAcquireLockException("lock timeout")).getName())
                .isEqualTo("lockTimeout");
    }

    @Test
    void databaseUnavailableIsARetryable503() {
        ResponseEntity<ProblemDetail> response =
                handler.databaseUnavailable(new CannotCreateTransactionException("Connection is not available"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getType()).hasToString("/problems/database-unavailable");
        assertThat(meters.counter("ledger.database.unavailable").count()).isEqualTo(1);
    }
}
