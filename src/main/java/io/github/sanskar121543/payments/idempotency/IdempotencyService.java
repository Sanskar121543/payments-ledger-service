package io.github.sanskar121543.payments.idempotency;

import io.github.sanskar121543.payments.web.Problems;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    public sealed interface Claim {
    }

    public record Acquired(IdempotencyRecord record) implements Claim {
    }

    public record Replay(StoredResponse response) implements Claim {
    }

    public record Mismatch() implements Claim {
    }

    private final IdempotencyRepository repository;
    private final ResponseFactory responses;

    public IdempotencyService(IdempotencyRepository repository, ResponseFactory responses) {
        this.repository = repository;
        this.responses = responses;
    }

    /**
     * Runs {@code work} at most once per (client, key). MANDATORY means this must join the caller's
     * transaction, so the key, the response and the ledger writes commit or roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResponse execute(String clientId, String key, String requestHash, Supplier<StoredResponse> work) {
        return switch (claim(clientId, key, requestHash)) {
            case Replay replay -> replay.response().asReplay();
            case Mismatch mismatch -> responses.problem(Problems.of(HttpStatus.CONFLICT, "idempotency-key-reused",
                    "Idempotency-Key reused with a different request",
                    "Key '" + key + "' was first used with a different request body or endpoint."));
            case Acquired acquired -> {
                StoredResponse response = work.get();
                acquired.record().complete(response);
                yield response;
            }
        };
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(String clientId, String key, String requestHash) {
        int inserted;
        try {
            inserted = repository.insertIfAbsent(clientId, key, requestHash);
        } catch (PessimisticLockingFailureException e) {
            // We waited lock_timeout for another transaction that holds this key and it has not finished.
            throw new IdempotencyKeyInUseException(key);
        }
        IdempotencyRecord record = repository.findByClientIdAndIdemKey(clientId, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after insert"));
        if (inserted == 1) {
            return new Acquired(record);
        }
        if (!record.getRequestHash().equals(requestHash)) {
            return new Mismatch();
        }
        if (!record.isCompleted()) {
            throw new IdempotencyKeyInUseException(key);
        }
        return new Replay(record.storedResponse());
    }
}
