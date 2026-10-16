package io.github.sanskar121543.payments.idempotency;

public class IdempotencyKeyInUseException extends RuntimeException {

    public IdempotencyKeyInUseException(String key) {
        super("A request with Idempotency-Key '" + key + "' is still in progress");
    }
}
