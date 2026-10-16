package io.github.sanskar121543.payments.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
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

    @Autowired
    DataSource dataSource;

    long transferId;
    long customerId;

    @BeforeEach
    void createATransfer() {
        ApiClient api = ApiClient.forPort(port);
        long alice = api.fundedAccount("alice", 1_000);
        long bob = api.createAccount("bob");
        transferId = api.transfer(alice, bob, 100).longField("id");
        customerId = bob;
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
    void aPendingTransferMayChangeOnlyItsStatus() throws SQLException {
        assertThatThrownBy(() -> updatePendingTransfer("status = 'COMPLETED', amount_cents = 999"))
                .hasMessageContaining("only the status of a pending transfer may change");
        assertThatThrownBy(() -> updatePendingTransfer("status = 'COMPLETED', correlation_id = 'forged'"))
                .hasMessageContaining("only the status of a pending transfer may change");
        assertThatThrownBy(() -> updatePendingTransfer("status = 'COMPLETED', served_by = 'someone-else'"))
                .hasMessageContaining("only the status of a pending transfer may change");
        updatePendingTransfer("status = 'COMPLETED'");
    }

    // Inserts a PENDING transfer and applies the update inside one transaction that is always rolled back.
    private void updatePendingTransfer(String setClause) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO transfers (status, from_account_id, to_account_id, amount_cents, served_by,
                                               correlation_id)
                        VALUES ('PENDING', 1, ?, 1, 'test', 'original') RETURNING id
                        """)) {
                    insert.setLong(1, customerId);
                    try (ResultSet rs = insert.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE transfers SET " + setClause + " WHERE id = ?")) {
                    update.setLong(1, id);
                    update.executeUpdate();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void completedTransfersCannotChangeStatusOrBeDeleted() {
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
