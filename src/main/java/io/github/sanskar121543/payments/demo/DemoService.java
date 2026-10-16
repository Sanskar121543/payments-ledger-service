package io.github.sanskar121543.payments.demo;

import io.github.sanskar121543.payments.demo.DemoReports.Attempt;
import io.github.sanskar121543.payments.demo.DemoReports.Balance;
import io.github.sanskar121543.payments.demo.DemoReports.ConcurrentReport;
import io.github.sanskar121543.payments.demo.DemoReports.RetryReport;
import io.github.sanskar121543.payments.demo.DemoHttpClient.Reply;
import io.github.sanskar121543.payments.ledger.InvariantService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the dashboard's two demos by sending real HTTP requests to the public API, through nginx in
 * compose. A browser cannot do this itself: it opens about 6 connections per host, so "100 concurrent"
 * from a page would really be about 6 at a time.
 */
@Service
class DemoService {

    private static final long FUNDING_ACCOUNT_ID = 1L;

    private final JsonMapper jsonMapper;
    private final InvariantService invariants;
    private final Environment environment;
    private final String configuredBaseUrl;

    DemoService(JsonMapper jsonMapper, InvariantService invariants, Environment environment,
            @Value("${payments.demo.base-url}") String configuredBaseUrl) {
        this.jsonMapper = jsonMapper;
        this.invariants = invariants;
        this.environment = environment;
        this.configuredBaseUrl = configuredBaseUrl;
    }

    RetryReport sameRequestTenTimes(String apiKey) {
        DemoHttpClient api = client(apiKey);
        String run = runId();
        long alice = createAccount(api, run + "-alice", 10_000);
        long bob = createAccount(api, run + "-bob", 0);
        Map<Long, String> names = Map.of(alice, run + "-alice", bob, run + "-bob");
        Map<Long, Long> before = balances(api, names.keySet());
        String key = run + "-same-key";

        List<Reply> replies = concurrently(10, i -> api.post("/v1/transfers",
                transferBody(alice, bob, 1_000), key));

        List<Attempt> attempts = replies.stream()
                .map(r -> new Attempt(r.status(), r.status() == 201 ? r.longField("id") : null, r.replayed(),
                        r.servedBy(), r.latencyMillis()))
                .toList();
        long distinct = attempts.stream().map(Attempt::transferId).filter(Objects::nonNull).distinct().count();
        return new RetryReport(key, replies.size(), attempts, distinct, byInstance(replies),
                balanceRows(names, before, balances(api, names.keySet())), invariants.check());
    }

    ConcurrentReport hundredConcurrentTransfers(String apiKey) {
        DemoHttpClient api = client(apiKey);
        String run = runId();
        Map<Long, String> names = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            String name = run + "-c" + i;
            names.put(createAccount(api, name, 10_000), name);
        }
        List<Long> accounts = List.copyOf(names.keySet());
        Map<Long, Long> before = balances(api, accounts);

        long started = System.nanoTime();
        List<Reply> replies = concurrently(100, i -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int from = random.nextInt(accounts.size());
            int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
            long amount = 100 + random.nextInt(4_900);
            return api.post("/v1/transfers", transferBody(accounts.get(from), accounts.get(to), amount),
                    run + "-t" + i);
        });
        long duration = (System.nanoTime() - started) / 1_000_000;

        Map<Long, Long> after = balances(api, accounts);
        long totalBefore = before.values().stream().mapToLong(Long::longValue).sum();
        long totalAfter = after.values().stream().mapToLong(Long::longValue).sum();
        List<Long> latencies = replies.stream().map(Reply::latencyMillis).sorted().toList();
        return new ConcurrentReport(replies.size(),
                replies.stream().collect(Collectors.groupingBy(Reply::status, TreeMap::new, Collectors.counting())),
                byInstance(replies), balanceRows(names, before, after), totalBefore, totalAfter,
                totalBefore == totalAfter, after.values().stream().allMatch(b -> b >= 0),
                percentile(latencies, 50), percentile(latencies, 95), duration, invariants.check());
    }

    private DemoHttpClient client(String apiKey) {
        String baseUrl = configuredBaseUrl.isBlank()
                ? "http://localhost:" + environment.getProperty("local.server.port")
                : configuredBaseUrl;
        return new DemoHttpClient(baseUrl, apiKey, jsonMapper);
    }

    private static long createAccount(DemoHttpClient api, String name, long fundingCents) {
        Reply created = api.post("/v1/accounts", Map.of("name", name), name + "-create");
        long id = created.longField("id");
        if (fundingCents > 0) {
            api.post("/v1/transfers", transferBody(FUNDING_ACCOUNT_ID, id, fundingCents), name + "-fund");
        }
        return id;
    }

    private static Map<Long, Long> balances(DemoHttpClient api, Iterable<Long> accounts) {
        Map<Long, Long> balances = new LinkedHashMap<>();
        for (long id : accounts) {
            balances.put(id, api.get("/v1/accounts/" + id).longField("balanceCents"));
        }
        return balances;
    }

    private static List<Balance> balanceRows(Map<Long, String> names, Map<Long, Long> before, Map<Long, Long> after) {
        return names.keySet().stream().sorted(Comparator.naturalOrder())
                .map(id -> new Balance(id, names.get(id), before.get(id), after.get(id)))
                .toList();
    }

    private static Map<String, Long> byInstance(List<Reply> replies) {
        return replies.stream().collect(Collectors.groupingBy(Reply::servedBy, TreeMap::new, Collectors.counting()));
    }

    private static Map<String, Object> transferBody(long from, long to, long amountCents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccount", from);
        body.put("toAccount", to);
        body.put("amountCents", amountCents);
        return body;
    }

    private static <T> List<T> concurrently(int count, IntFunction<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.apply(index);
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Demo run failed: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static String runId() {
        return "demo-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
