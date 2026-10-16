package io.github.sanskar121543.payments.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.payments.support.ApiClient;
import io.github.sanskar121543.payments.support.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kills app2 with SIGKILL in the middle of a 600-transfer run. Clients retry with the same
 * Idempotency-Key, exactly as a real client would, and the money must still add up to the cent.
 */
class InstanceFailoverIT {

    private static final Logger log = LoggerFactory.getLogger(InstanceFailoverIT.class);
    private static final int TRANSFERS = 600;
    private static final int KILL_AFTER_COMPLETED = 200;
    private static final Set<Integer> RETRYABLE = Set.of(409, 502, 503, 504);

    static LedgerTopology topology;
    static ApiClient api;

    @BeforeAll
    static void start() {
        topology = LedgerTopology.start(List.of("app1", "app2", "app3"), true);
        api = topology.viaNginx();
    }

    @AfterAll
    static void stop() {
        topology.close();
    }

    @Test
    void killingOneInstanceMidRunLosesAndDuplicatesNothing() throws Exception {
        long[] accounts = LongStream.range(0, 10).map(i -> api.fundedAccount("f" + i, 50_000)).toArray();
        Random random = new Random(11);
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            int from = random.nextInt(accounts.length);
            int to = (from + 1 + random.nextInt(accounts.length - 1)) % accounts.length;
            attempts.add(new Attempt(UUID.randomUUID().toString(), accounts[from], accounts[to],
                    100 + random.nextInt(9_900)));
        }

        AtomicInteger finished = new AtomicInteger();
        AtomicBoolean killed = new AtomicBoolean();
        AtomicReference<Instant> killedAt = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Attempt>> futures = new ArrayList<>();
        for (Attempt attempt : attempts) {
            futures.add(pool.submit(() -> {
                attempt.sendUntilFinal();
                if (finished.incrementAndGet() >= KILL_AFTER_COMPLETED && killed.compareAndSet(false, true)) {
                    LedgerTopology.kill(topology.app("app2"));
                    killedAt.set(Instant.now());
                    log.info("SIGKILLed app2 after {} finished transfers", KILL_AFTER_COMPLETED);
                }
                return attempt;
            }));
        }
        for (Future<Attempt> future : futures) {
            future.get(5, TimeUnit.MINUTES);
        }
        pool.shutdown();

        assertThat(killed).isTrue();
        assertThat(attempts).allSatisfy(a -> assertThat(a.finalResponse.status()).isIn(201, 422));

        JdbcTemplate jdbc = topology.jdbc();
        for (Attempt attempt : attempts) {
            Map<String, Object> stored = jdbc.queryForMap(
                    "SELECT response_status, response_body FROM idempotency_keys WHERE client_id = 'test' AND idem_key = ?",
                    attempt.key);
            assertThat(stored.get("response_status")).isEqualTo(attempt.finalResponse.status());
            assertThat(stored.get("response_body")).isEqualTo(attempt.finalResponse.rawBody());
        }

        long created = attempts.stream().filter(a -> a.finalResponse.status() == 201).count();
        Long completedInDb = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transfers
                WHERE status = 'COMPLETED' AND from_account_id = ANY(?) AND to_account_id = ANY(?)
                """, Long.class, accounts, accounts);
        assertThat(completedInDb).as("one transfer per 201, none extra").isEqualTo(created);

        assertThat(LongStream.of(accounts).map(api::balance).sum()).isEqualTo(10 * 50_000);
        assertThat(api.invariants().get("ok").asBoolean()).isTrue();

        List<String> servedBy = jdbc.queryForList(
                "SELECT DISTINCT served_by FROM transfers WHERE from_account_id = ANY(?)", String.class, accounts);
        assertThat(servedBy).as("app2 did serve transfers before it died").contains("app2");
        Instant settled = killedAt.get().plus(Duration.ofSeconds(5));
        assertThat(attempts.stream().filter(a -> a.finishedAt.isAfter(settled)))
                .noneMatch(a -> "app2".equals(a.finalResponse.header("X-Served-By")));

        log.info("finals={} clientRetries={}", attempts.stream()
                        .collect(Collectors.groupingBy(a -> a.finalResponse.status(), Collectors.counting())),
                attempts.stream().mapToInt(a -> a.retries).sum());
    }

    private static final class Attempt {

        final String key;
        final long from;
        final long to;
        final long amount;
        ApiResponse finalResponse;
        Instant finishedAt;
        int retries;

        Attempt(String key, long from, long to, long amount) {
            this.key = key;
            this.from = from;
            this.to = to;
            this.amount = amount;
        }

        void sendUntilFinal() throws InterruptedException {
            long backoffMillis = 100;
            for (int attempt = 0; attempt < 15; attempt++) {
                try {
                    ApiResponse response = api.transfer(from, to, amount, key);
                    if (!RETRYABLE.contains(response.status())) {
                        finalResponse = response;
                        finishedAt = Instant.now();
                        return;
                    }
                } catch (ApiClient.UncheckedIOException connectionDropped) {
                    // The instance died mid-request: retry with the same key, like a real client.
                }
                retries++;
                Thread.sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, 1_000);
            }
            throw new AssertionError("No final answer for key " + key + " after 15 attempts");
        }
    }
}
