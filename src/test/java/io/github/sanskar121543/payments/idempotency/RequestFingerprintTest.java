package io.github.sanskar121543.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sameRequestGivesTheSameHash() {
        assertThat(RequestFingerprint.of("POST", "/v1/transfers", 1L, 2L, 500L))
                .isEqualTo(RequestFingerprint.of("POST", "/v1/transfers", 1L, 2L, 500L))
                .hasSize(64);
    }

    @Test
    void anyChangedFieldChangesTheHash() {
        String original = RequestFingerprint.of("POST", "/v1/transfers", 1L, 2L, 500L);

        assertThat(RequestFingerprint.of("POST", "/v1/transfers", 1L, 2L, 501L)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("POST", "/v1/transfers", 2L, 1L, 500L)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("POST", "/v1/accounts", 1L, 2L, 500L)).isNotEqualTo(original);
    }

    @Test
    void partsCannotBleedIntoEachOther() {
        assertThat(RequestFingerprint.of("POST", "/x", "ab", "c"))
                .isNotEqualTo(RequestFingerprint.of("POST", "/x", "a", "bc"));
    }
}
