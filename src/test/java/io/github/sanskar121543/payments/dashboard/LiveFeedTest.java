package io.github.sanskar121543.payments.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sanskar121543.payments.activity.ActivityItem;
import io.github.sanskar121543.payments.activity.ActivityService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LiveFeedTest {

    @Test
    void aClientThatStopsReadingDoesNotStallTheSchedulerThread() throws Exception {
        ActivityService activity = mock(ActivityService.class);
        when(activity.latestId()).thenReturn(0L);
        when(activity.after(anyLong(), anyInt())).thenReturn(items(1_000));
        StalledEmitter stalled = new StalledEmitter();
        LiveFeed feed = new LiveFeed(activity) {
            @Override
            SseEmitter newEmitter() {
                return stalled;
            }
        };

        feed.subscribe(null);
        assertThat(stalled.writing.await(5, TimeUnit.SECONDS)).as("sender stuck in its first write").isTrue();

        // 1,000 events fill the queue; the heartbeat overflows it.
        CompletableFuture<Void> tick = CompletableFuture.runAsync(() -> {
            feed.push();
            feed.heartbeat();
        });
        tick.get(2, TimeUnit.SECONDS);
        assertThat(feed.subscriberCount()).isZero();

        stalled.release.countDown();
        assertThat(stalled.completed.await(5, TimeUnit.SECONDS)).as("completed by the sender thread").isTrue();
    }

    private static List<ActivityItem> items(int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(id -> new ActivityItem(id, UUID.randomUUID(), id, "TRANSFER", 2, 3, 100, "app1", "app1",
                        "c-" + id, Instant.EPOCH, Instant.EPOCH, false, false))
                .toList();
    }

    /** A client that stopped reading: the write never returns until released. */
    private static final class StalledEmitter extends SseEmitter {

        // Stands in for ResponseBodyEmitter's write lock, which send() holds and complete() waits for.
        final ReentrantLock writeLock = new ReentrantLock();
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder event) {
            writeLock.lock();
            try {
                writing.countDown();
                awaitIgnoringInterrupts(release);
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void complete() {
            writeLock.lock();
            try {
                completed.countDown();
            } finally {
                writeLock.unlock();
            }
        }

        // A blocked socket write does not return early because its thread was interrupted.
        private static void awaitIgnoringInterrupts(CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
