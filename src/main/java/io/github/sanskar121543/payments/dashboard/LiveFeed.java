package io.github.sanskar121543.payments.dashboard;

import io.github.sanskar121543.payments.activity.ActivityItem;
import io.github.sanskar121543.payments.activity.ActivityService;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
    // Ids are handed out at insert but become visible at commit, so a row can show up after rows with
    // larger ids. Every tick re-reads this many rows behind the newest one sent and fills such gaps.
    private static final int LOOKBACK = 500;
    private static final int BATCH = 500;
    private static final int REMEMBERED_IDS = 2_000;
    private static final int QUEUE_CAPACITY = 1_000;

    private final ActivityService activity;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public LiveFeed(ActivityService activity) {
        this.activity = activity;
    }

    public SseEmitter subscribe(Long lastEventId) {
        SseEmitter emitter = newEmitter();
        long resumeFloor = Math.max(0, activity.latestId() - BACKLOG);
        // Clamped: an old or forged Last-Event-ID must not make this instance replay the whole table.
        long floor = lastEventId == null ? resumeFloor : Math.max(lastEventId, resumeFloor);
        Subscriber subscriber = new Subscriber(emitter, floor);
        emitter.onCompletion(subscriber::close);
        emitter.onTimeout(subscriber::close);
        emitter.onError(error -> subscriber.close());
        subscribers.add(subscriber);
        subscriber.offer(SseEmitter.event().comment("connected"));
        return emitter;
    }

    SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    }

    // Runs on the scheduler thread but never touches a socket: it only fills each subscriber's queue.
    @Scheduled(fixedDelay = 1000)
    void push() {
        if (subscribers.isEmpty()) {
            return;
        }
        long from = subscribers.stream().mapToLong(s -> s.highestSent).min().orElse(0) - LOOKBACK;
        List<ActivityItem> items = activity.after(Math.max(0, from), LOOKBACK + BATCH);
        for (Subscriber subscriber : subscribers) {
            for (ActivityItem item : items) {
                if (item.id() > subscriber.floor && subscriber.sent.add(item.id())) {
                    subscriber.highestSent = Math.max(subscriber.highestSent, item.id());
                    subscriber.offer(SseEmitter.event().id(String.valueOf(item.id())).name("activity").data(item));
                }
            }
        }
    }

    // nginx and browsers drop idle connections; a comment line every 15s keeps the stream open.
    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        subscribers.forEach(subscriber -> subscriber.offer(SseEmitter.event().comment("keep-alive")));
    }

    @EventListener(ContextClosedEvent.class)
    void closeStreams() {
        subscribers.forEach(subscriber -> subscriber.emitter.complete());
    }

    int subscriberCount() {
        return subscribers.size();
    }

    private final class Subscriber {

        final SseEmitter emitter;
        final long floor;
        volatile long highestSent;
        // Only touched by the scheduler thread.
        final Set<Long> sent = Collections.newSetFromMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > REMEMBERED_IDS;
            }
        });
        final BlockingQueue<SseEmitter.SseEventBuilder> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        final Thread sender;
        volatile boolean overflowed;

        Subscriber(SseEmitter emitter, long floor) {
            this.emitter = emitter;
            this.floor = floor;
            this.highestSent = floor;
            // One virtual thread per subscriber does the blocking socket writes, so a client that stops
            // reading only stalls its own stream.
            this.sender = Thread.ofVirtual().name("sse-subscriber").start(this::drain);
        }

        void offer(SseEmitter.SseEventBuilder event) {
            if (!queue.offer(event)) {
                // Too far behind: end the stream; the browser reconnects with Last-Event-ID. The sender
                // thread completes the emitter, because complete() waits for the write in progress, and
                // that write is exactly what is stuck.
                overflowed = true;
                close();
            }
        }

        private void drain() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    emitter.send(queue.take());
                }
            } catch (InterruptedException closed) {
                // Ended by close(); completed below if it overflowed.
            } catch (IOException | IllegalStateException disconnected) {
                close();
                return;
            }
            if (overflowed) {
                emitter.complete();
            }
        }

        void close() {
            subscribers.remove(this);
            sender.interrupt();
        }
    }
}
