package io.github.sanskar121543.payments.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sanskar121543.payments.common.SingletonJobLock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxRelayTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SingletonJobLock jobLock = mock(SingletonJobLock.class);
    private final OutboxRelay relay = new OutboxRelay(repository, kafka, jobLock, 100, Duration.ofMillis(200),
            Duration.ofSeconds(4));

    private final List<OutboxEvent> batch = List.of(event("1"), event("2"), event("3"));

    @BeforeEach
    void givenThreeUnpublishedEvents() {
        when(jobLock.tryAcquire(anyInt())).thenReturn(true);
        when(repository.findByPublishedAtIsNullOrderByIdAsc(any())).thenReturn(batch);
    }

    @Test
    void publishesOldestFirstAndMarksEveryAcknowledgedEvent() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(acked());

        assertThat(relay.relayBatch()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(3)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::key).containsExactly("1", "2", "3");
        assertThat(sent.getAllValues().getFirst().headers().lastHeader("eventId")).isNotNull();
        assertThat(batch).allMatch(e -> e.getPublishedAt() != null);
        verify(repository, times(3)).flush();
    }

    @Test
    void stopsAtTheFirstFailureSoNoLaterEventOvertakesIt() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(acked())
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(relay.relayBatch()).isEqualTo(1);

        verify(kafka, times(2)).send(any(ProducerRecord.class));
        assertThat(batch.get(0).getPublishedAt()).isNotNull();
        assertThat(batch.get(1).getPublishedAt()).isNull();
        assertThat(batch.get(2).getPublishedAt()).isNull();
    }

    @Test
    void anUnansweredSendTimesOutInsteadOfBlockingTheRelay() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(acked())
                .thenReturn(new CompletableFuture<>());

        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(batch.get(1).getPublishedAt()).isNull();
    }

    @Test
    void stopsStartingNewSendsOnceTheBatchTimeBudgetIsUsedUp() {
        OutboxRelay noTimeLeft = new OutboxRelay(repository, kafka, jobLock, 100, Duration.ofMillis(200),
                Duration.ZERO);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(acked());

        assertThat(noTimeLeft.relayBatch()).isZero();

        verifyNoInteractions(kafka);
    }

    @Test
    void doesNothingWhileAnotherInstanceHoldsTheRelayLock() {
        when(jobLock.tryAcquire(SingletonJobLock.OUTBOX_RELAY)).thenReturn(false);

        assertThat(relay.relayBatch()).isZero();

        verifyNoInteractions(kafka);
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    private static OutboxEvent event(String key) {
        return new OutboxEvent(UUID.randomUUID(), "transfer.completed", key, "{}", "corr-" + key);
    }
}
