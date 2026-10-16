package io.github.sanskar121543.payments.ledger;

import io.github.sanskar121543.payments.common.DbTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private long transferId;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(long transferId, long accountId, long amountCents) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.amountCents = amountCents;
        this.createdAt = DbTime.now();
    }

    public static LedgerEntry debit(long transferId, long accountId, long amountCents) {
        return new LedgerEntry(transferId, accountId, -amountCents);
    }

    public static LedgerEntry credit(long transferId, long accountId, long amountCents) {
        return new LedgerEntry(transferId, accountId, amountCents);
    }

    public Long getId() {
        return id;
    }

    public long getTransferId() {
        return transferId;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
