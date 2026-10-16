package io.github.sanskar121543.payments.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

    private static final String DEMO_HASH = HexFormat.of().formatHex(ApiKeys.sha256("demo-key"));
    private static final String CI_HASH = HexFormat.of().formatHex(ApiKeys.sha256("ci-key"));

    private final ApiKeys apiKeys = new ApiKeys("demo:" + DEMO_HASH + ", ci:" + CI_HASH);

    @Test
    void resolvesTheClientOwningTheKey() {
        assertThat(apiKeys.clientFor("demo-key")).contains("demo");
        assertThat(apiKeys.clientFor("ci-key")).contains("ci");
    }

    @Test
    void rejectsUnknownOrMissingKeys() {
        assertThat(apiKeys.clientFor("wrong")).isEmpty();
        assertThat(apiKeys.clientFor("")).isEmpty();
        assertThat(apiKeys.clientFor(null)).isEmpty();
    }

    @Test
    void refusesToStartWithoutKeysOrWithMalformedConfig() {
        assertThatThrownBy(() -> new ApiKeys("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeys("no-colon")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeys("demo:abcd")).isInstanceOf(IllegalStateException.class);
    }
}
