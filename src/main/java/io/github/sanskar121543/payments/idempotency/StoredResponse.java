package io.github.sanskar121543.payments.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public record StoredResponse(int status, String body, boolean replayed) {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    public StoredResponse asReplay() {
        return new StoredResponse(status, body, true);
    }

    public ResponseEntity<String> toResponseEntity() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(status >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
        if (replayed) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}
