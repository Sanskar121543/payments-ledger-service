package io.github.sanskar121543.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IdempotencyPurgeIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdempotencyPurgeJob purgeJob;

    @Test
    void purgeRemovesOnlyKeysOlderThanTheRetentionWindow() {
        String oldKey = "old-" + UUID.randomUUID();
        String freshKey = "fresh-" + UUID.randomUUID();
        insertKey(oldKey, "now() - interval '25 hours'");
        insertKey(freshKey, "now() - interval '23 hours'");

        purgeJob.purge();

        assertThat(exists(oldKey)).isFalse();
        assertThat(exists(freshKey)).isTrue();
    }

    private void insertKey(String key, String createdAt) {
        jdbc.update("INSERT INTO idempotency_keys (client_id, idem_key, request_hash, response_status, response_body,"
                + " created_at, completed_at) VALUES ('test', ?, 'h', 201, '{}', " + createdAt + ", now())", key);
    }

    private boolean exists(String key) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = ?",
                Integer.class, key);
        return count != null && count > 0;
    }
}
