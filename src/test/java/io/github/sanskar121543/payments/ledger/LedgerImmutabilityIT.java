package io.github.sanskar121543.payments.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class LedgerImmutabilityIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    long transferId;

    @BeforeEach
    void createATransfer() {
        ApiClient api = ApiClient.forPort(port);
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");
        transferId = api.transfer(alice, bob, 100).longField("id");
    }

    @Test
    void ledgerEntriesCannotBeUpdatedDeletedOrTruncated() {
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_cents = 1 WHERE transfer_id = ?", transferId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transfer_id = ?", transferId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries CASCADE"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void completedTransfersCannotChangeStatusAmountOrBeDeleted() {
        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status = 'PENDING' WHERE id = ?", transferId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("illegal transfer status change");
        assertThatThrownBy(() -> jdbc.update("UPDATE transfers SET status = 'FAILED', failure_reason = 'x' WHERE id = ?", transferId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("illegal transfer status change");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transfers WHERE id = ?", transferId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void customerBalancesCannotGoNegativeEvenWithDirectSql() {
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance_cents = -1 WHERE type = 'CUSTOMER'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("no_customer_overdraft");
    }
}
