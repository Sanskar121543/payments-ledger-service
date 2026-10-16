package io.github.sanskar121543.payments.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

    @Test
    void prefersTheClientCorrelationIdThenTheNginxRequestId() {
        assertThat(CorrelationIdFilter.pick("client-id-1", "nginx-id")).isEqualTo("client-id-1");
        assertThat(CorrelationIdFilter.pick(null, "abc123")).isEqualTo("abc123");
    }

    @Test
    void replacesUnsafeValuesWithAFreshUuid() {
        String picked = CorrelationIdFilter.pick("bad\nvalue", "also bad!");

        assertThat(picked).matches("[0-9a-f-]{36}");
    }
}
