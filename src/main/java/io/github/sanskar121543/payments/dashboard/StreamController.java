package io.github.sanskar121543.payments.dashboard;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class StreamController {

    private final LiveFeed liveFeed;

    StreamController(LiveFeed liveFeed) {
        this.liveFeed = liveFeed;
    }

    @GetMapping(path = "/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
            HttpServletResponse response) {
        // Tells nginx not to buffer this response, so events reach the browser immediately.
        response.setHeader("X-Accel-Buffering", "no");
        return liveFeed.subscribe(lastEventId);
    }
}
