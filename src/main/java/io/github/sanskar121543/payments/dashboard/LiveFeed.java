package io.github.sanskar121543.payments.dashboard;

import io.github.sanskar121543.payments.activity.ActivityItem;
import io.github.sanskar121543.payments.activity.ActivityService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams the activity feed to dashboards connected to this instance. Each instance tails the shared
 * activity_feed table, so a browser sees every transfer no matter which instance it is connected to or
 * which instance consumed the Kafka event.
 */
@Component
public class LiveFeed {

    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int BACKLOG = 25;
    private static final int BATCH = 200;

    private final ActivityService activity;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public LiveFeed(ActivityService activity) {
        this.activity = activity;
    }

    public SseEmitter subscribe(Long lastEventId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        long latest = activity.latestSettledId();
        long cursor = lastEventId != null ? lastEventId : Math.max(0, latest - BACKLOG);
        Subscriber subscriber = new Subscriber(emitter, cursor);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(error -> subscribers.remove(subscriber));
        subscribers.add(subscriber);
        send(subscriber, SseEmitter.event().comment("connected"));
        return emitter;
    }

    @Scheduled(fixedDelay = 1000)
    void push() {
        if (subscribers.isEmpty()) {
            return;
        }
        long from = subscribers.stream().mapToLong(s -> s.cursor).min().orElse(0);
        List<ActivityItem> items = activity.settledAfter(from, BATCH);
        for (Subscriber subscriber : subscribers) {
            for (ActivityItem item : items) {
                if (item.id() > subscriber.cursor) {
                    send(subscriber, SseEmitter.event().id(String.valueOf(item.id())).name("activity").data(item));
                    subscriber.cursor = item.id();
                }
            }
        }
    }

    // nginx and browsers drop idle connections; a comment line every 15s keeps the stream open.
    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        subscribers.forEach(subscriber -> send(subscriber, SseEmitter.event().comment("keep-alive")));
    }

    @EventListener(ContextClosedEvent.class)
    void closeStreams() {
        subscribers.forEach(subscriber -> subscriber.emitter.complete());
        subscribers.clear();
    }

    int subscriberCount() {
        return subscribers.size();
    }

    private void send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter.send(event);
        } catch (IOException | IllegalStateException disconnected) {
            subscribers.remove(subscriber);
        }
    }

    private static final class Subscriber {

        final SseEmitter emitter;
        volatile long cursor;

        Subscriber(SseEmitter emitter, long cursor) {
            this.emitter = emitter;
            this.cursor = cursor;
        }
    }
}
