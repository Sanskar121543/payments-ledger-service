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
     *
     * TODO(practice): switch on claim(...).
     * - Replay: the stored response, marked as a replay (asReplay()).
     * - Mismatch: a 409 problem of type "idempotency-key-reused", built with responses.problem(Problems.of(...)).
     * - Acquired: run work, store its response on the record (complete), return it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResponse execute(String clientId, String key, String requestHash, Supplier<StoredResponse> work) {
        throw new UnsupportedOperationException("practice: write IdempotencyService.execute");
    }

    /**
     * TODO(practice) rules:
     * - Insert the key with repository.insertIfAbsent (INSERT ... ON CONFLICT DO NOTHING). If another open
     *   transaction is inserting the same key, this insert waits for it. When lock_timeout ends that wait,
     *   you get a PessimisticLockingFailureException: throw IdempotencyKeyInUseException(key).
     * - Then load the row. Inserted by us: Acquired. Different requestHash: Mismatch. Not completed yet:
     *   IdempotencyKeyInUseException. Completed: Replay of its stored response.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(String clientId, String key, String requestHash) {
        throw new UnsupportedOperationException("practice: write IdempotencyService.claim");
    }
}
