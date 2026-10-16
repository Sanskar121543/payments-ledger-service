package io.github.sanskar121543.payments.idempotency;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes responses once, up front, so the exact bytes can be stored with the idempotency key
 * and replayed later.
 */
@Component
public class ResponseFactory {

    private final JsonMapper jsonMapper;

    public ResponseFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public StoredResponse json(int status, Object body) {
        return new StoredResponse(status, jsonMapper.writeValueAsString(body), false);
    }

    public StoredResponse problem(ProblemDetail problem) {
        return new StoredResponse(problem.getStatus(), jsonMapper.writeValueAsString(problem), false);
    }
}
