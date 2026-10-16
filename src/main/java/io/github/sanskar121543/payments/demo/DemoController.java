package io.github.sanskar121543.payments.demo;

import io.github.sanskar121543.payments.web.ApiKeyFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
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

    DemoController(DemoService demo) {
        this.demo = demo;
    }

    @PostMapping("/retry-same-request")
    DemoReports.RetryReport retrySameRequest(@RequestHeader(ApiKeyFilter.HEADER) String apiKey) {
        return demo.sameRequestTenTimes(apiKey);
    }

    @PostMapping("/concurrent-transfers")
    DemoReports.ConcurrentReport concurrentTransfers(@RequestHeader(ApiKeyFilter.HEADER) String apiKey) {
        return demo.hundredConcurrentTransfers(apiKey);
    }
}
