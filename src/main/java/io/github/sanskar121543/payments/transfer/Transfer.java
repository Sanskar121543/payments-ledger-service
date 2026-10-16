package io.github.sanskar121543.payments.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "transfers")
public class Transfer {

    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "from_account_id", nullable = false, updatable = false)
    private long fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private long toAccountId;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() {
    }

    private Transfer(long fromAccountId, long toAccountId, long amountCents, String correlationId) {
        this.status = TransferStatus.PENDING;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amountCents = amountCents;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    public static Transfer pending(long fromAccountId, long toAccountId, long amountCents, String correlationId) {
        return new Transfer(fromAccountId, toAccountId, amountCents, correlationId);
    }

    public void complete() {
        requirePending();
        status = TransferStatus.COMPLETED;
    }

    public void fail(String reason) {
        requirePending();
        status = TransferStatus.FAILED;
        failureReason = reason;
    }

    private void requirePending() {
        if (status != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer " + id + " is already " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public long getFromAccountId() {
        return fromAccountId;
    }

    public long getToAccountId() {
        return toAccountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
