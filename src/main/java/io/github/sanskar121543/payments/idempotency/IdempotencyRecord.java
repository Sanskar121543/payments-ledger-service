package io.github.sanskar121543.payments.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "idem_key", nullable = false, updatable = false)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
    }

    public void complete(StoredResponse response) {
        if (isCompleted()) {
            throw new IllegalStateException("Idempotency key " + idemKey + " already has a stored response");
        }
        this.responseStatus = response.status();
        this.responseBody = response.body();
        this.completedAt = Instant.now();
    }

    public boolean isCompleted() {
        return responseStatus != null;
    }

    public StoredResponse storedResponse() {
        return new StoredResponse(responseStatus, responseBody, false);
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
