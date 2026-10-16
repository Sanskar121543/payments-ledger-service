package io.github.sanskar121543.payments.demo;

import io.github.sanskar121543.payments.web.ApiKeyFilter;
import io.github.sanskar121543.payments.web.Problems;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Not idempotent on purpose (each click is a new run), so nginx never retries these routes. */
@RestController
@RequestMapping("/v1/demo")
@ConditionalOnBooleanProperty("payments.demo.enabled")
class DemoController {

    private final DemoService demo;
    // One run per instance at a time: each run starts 100 threads and sends 100+ requests.
    private final Semaphore oneRunAtATime = new Semaphore(1);

    DemoController(DemoService demo) {
        this.demo = demo;
    }

    @PostMapping("/retry-same-request")
    ResponseEntity<?> retrySameRequest(@RequestHeader(ApiKeyFilter.HEADER) String apiKey) {
        return runAlone(() -> demo.sameRequestTenTimes(apiKey));
    }

    @PostMapping("/concurrent-transfers")
    ResponseEntity<?> concurrentTransfers(@RequestHeader(ApiKeyFilter.HEADER) String apiKey) {
        return runAlone(() -> demo.hundredConcurrentTransfers(apiKey));
    }

    private ResponseEntity<?> runAlone(Supplier<Object> run) {
        if (!oneRunAtATime.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Problems.of(HttpStatus.TOO_MANY_REQUESTS,
                    "demo-busy", "A demo is already running", "Wait for it to finish, then try again."));
        }
        try {
            return ResponseEntity.ok(run.get());
        } finally {
            oneRunAtATime.release();
        }
    }
}
