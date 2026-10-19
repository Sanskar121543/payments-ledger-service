# Design decisions

Each entry gives the **choice**, the **alternatives**, and **why**. Numbers come from committed files in `results/`.
Entries marked **(default)** were open decisions in [PLAN.md](PLAN.md) §2. I picked a default while you were away, and each is easy to change.

## 1. Money is integer cents

- **Choice:**
  - `long` in Java and `BIGINT` in Postgres, in cents.
  - Jackson rejects `10.99` for an integer field: `accept-float-as-int: false`. Jackson 3 accepts and silently truncates it by default.
  - Jackson also rejects unknown fields.
  - Amounts must be `> 0` and at most $1,000,000.
- **Alternatives:** `BigDecimal` with a scale, or `double`.
- **Why:** integer cents cannot have rounding errors, and the arithmetic is trivial to reason about. `double` can't represent 0.1 exactly. Silently truncating `10.99` to `10` would be a real money bug, so `TransferIT` checks it.

## 2. Double-entry ledger with signed entries, and a funding account that may go negative

- **Choice:**
  - Each completed transfer writes two `ledger_entries` rows: `-amount` for the sender (debit) and `+amount` for the receiver (credit).
  - All test money comes from one `FUNDING` account, the only account allowed below zero. Its negative balance is the total money issued.
  - Customer balances are protected by `CHECK (type = 'FUNDING' OR balance_cents >= 0)`.
- **Alternatives:**
  - Separate debit and credit columns.
  - An "opening balance" equity account.
- **Why:** with signed amounts, "the ledger balances" is one query (`SUM(amount_cents) = 0`). The funding account is how money enters a closed double-entry system, and its balance shows exactly how much has been issued.

## 3. Append-only, enforced by the database

- **Choice:**
  - Triggers raise an error on `UPDATE`, `DELETE` and `TRUNCATE` of `ledger_entries`.
  - On `transfers`, a trigger allows exactly one change: `status` from `PENDING` to `COMPLETED`/`FAILED`.
  - Corrections are reversals (§8).
- **Alternatives:**
  - `REVOKE UPDATE` (the table owner can re-grant).
  - Application discipline only.
- **Why:** the guarantee holds even for a buggy code path or a manual SQL session. Limitation: a superuser can disable triggers, so this is defense in depth.

## 4. `PENDING` as a real, database-enforced state machine (default D2)

- **Choice:** a transfer is inserted as `PENDING`, then moved to `COMPLETED` or `FAILED` inside the same transaction. The trigger rejects every other change.
- **Alternative:** insert the final state directly. That is the simpler choice, and it keeps `transfers` fully append-only.
- **Why:** your spec asked for `PENDING → COMPLETED/FAILED`. Being honest about it: inside one transaction, nobody outside can ever see `PENDING`. The state would matter once a step happens outside our database, such as an external payment rail. Switching to "final at insert" is a small change in `Transfer` and the V1 trigger.

## 5. A cached balance on the account, with the ledger as the source of truth

- **Choice:**
  - `accounts.balance_cents` is updated in the same transaction as the entries, while the row lock is held.
  - `GET /v1/ledger/invariants` checks, in one `REPEATABLE READ` snapshot:
    - all entries sum to 0;
    - every cached balance equals the sum of that account's entries;
    - every completed transfer has exactly two entries summing to 0;
    - no customer is below zero.
- **Alternatives:** compute balances with `SUM()` on every read.
- **Why:** the balance check needs the row lock anyway, so keeping the cache costs nothing. The invariant endpoint proves the cache is never trusted blindly.

## 6. Concurrency: row locks in a fixed order

- **Choice:** before reading balances, lock both account rows with `@Lock(PESSIMISTIC_WRITE)`, **smaller id first**. Hibernate 7 renders this as `SELECT … FOR NO KEY UPDATE` on Postgres.
- **Why the order matters:** if A→B locks A then B, while B→A locks B then A, each can hold one lock and wait for the other forever. If everyone locks the smaller id first, they queue on the same first lock and a cycle is impossible.
- **Evidence:** [`results/phase-2-before-locking.md`](results/phase-2-before-locking.md). Without locks, the phase-1 code returned 22 server errors out of 100 concurrent transfers and let ten $10 transfers out of a $50 account succeed. The test log showed 195 deadlocks.
- **Alternatives:**

  | Option | Why not here |
  |---|---|
  | Optimistic locking (`@Version` + retry) | Retry storms on the hot funding account |
  | `SERIALIZABLE` | Every conflict becomes an error the app must retry |
  | Advisory locks | Nothing forces every code path to take them |
  | A guarded `UPDATE … WHERE balance >= x` | A transfer touches two rows, and FAILED attempts must be recorded |
- **Why `FOR NO KEY UPDATE` rather than `FOR UPDATE`:** it still excludes other writers. Unlike `FOR UPDATE`, it lets other transactions' foreign-key checks on the account through.
- **Safety nets** (all set per connection, not server-wide, so a test's own session is unaffected):
  - **`lock_timeout = 2s`:** an account-lock timeout returns `503 + Retry-After`.
  - **Pool exhausted or database unreachable:** no connection within Hikari's 5 s `connection-timeout` (for example, every connection waiting on a hot row) also returns `503 database-unavailable + Retry-After`, not a generic 500. A retry with the same key is safe, even if the failure hit a commit.
  - **`idle_in_transaction_session_timeout = 10s`.**
  - **On the server, `client_connection_check_interval = 1s`:** Postgres notices a killed instance quickly and releases its locks.

## 7. Idempotency: claim the key first, in the same transaction

- **Choice:**
  1. Inside the transfer's transaction, first run `INSERT INTO idempotency_keys … ON CONFLICT (client_id, idem_key) DO NOTHING`.
  2. **Inserted:** do the work, then store the exact response bytes on that row before `COMMIT`.
  3. **Conflict with the same request hash:** replay the stored bytes, with `Idempotent-Replayed: true`.
  4. **Conflict with a different hash:** `409 /problems/idempotency-key-reused` (default D1, as your spec says).
- **Concurrent duplicates:** Postgres makes a second insert of the same key **wait** for the first transaction. When it commits, the second request sees the committed row and replays it. If the wait exceeds `lock_timeout`, the answer is `409 /problems/idempotency-key-in-use` with `Retry-After: 1`. `IdempotencyIT` proves this case live.
- **What is stored:**
  - **Stored and replayed:** anything decided after the claim (success, insufficient funds, unknown account).
  - **Not stored:** validation errors, bad JSON and bad API keys, so the client can fix the request and reuse the key.
  - **Crashes:** a crash rolls everything back, so the key is released automatically.
- **Enforced by code:** `IdempotencyService` uses `@Transactional(propagation = MANDATORY)`, so it cannot run outside the caller's transaction.
- **Request hash:** SHA-256 over method, path and the validated fields. Each part is length-prefixed, so `("ab","c")` and `("a","bc")` hash differently. JSON whitespace and field order don't matter.
- **Scope and retention:** keys are per API client and deleted after 24 h by an hourly job (default D4).
- **Alternatives:**
  - Check-then-insert, which lets two concurrent requests both pass.
  - A Redis key store, which is not in the same transaction as the ledger.
  - The IETF draft's `422` for a reused key with a different body. That is a one-line change in `IdempotencyService`.
- **Why:** "stored in the SAME transaction" means there is never a state where the money moved but the key is missing, or the key exists but the money didn't move.

## 8. Reversals

- **Choice:**
  - `POST /v1/transfers/{id}/reversals` creates a new `REVERSAL` transfer with opposite entries; the original is untouched.
  - Full reversals only.
  - A partial unique index allows at most one *completed* reversal per transfer.
  - "Already reversed?" is checked **after** taking the account locks: a concurrent reversal holds the same locks, so its result is visible by then.
  - If the recipient has already spent the money, the reversal is recorded as `FAILED` (422). There are no overdrafts, not even for corrections.
- **Why:** corrections are new entries, as the spec requires. The unique index is the backstop if the code is ever wrong.

## 9. API key authentication

- **Choice:**
  - A `OncePerRequestFilter` checks `X-API-Key` on **every path except an allowlist**: the dashboard's static files and `/actuator/**`. Actuator stays open inside the network so Prometheus can scrape; nginx hides it from outside (§12).
  - The allowlist is checked against the **decoded path without `;` parameters**, which is what Spring MVC routes on. An earlier version checked the raw URI for a `/v1/` prefix, so `/v1;x/accounts` or `/%761/accounts` skipped the check while still reaching the controller. The code review caught it, and `AccountApiIT.pathTricksDoNotSkipTheKeyCheck` now covers it.
  - The server knows only SHA-256 hashes, from `.env` (`API_KEY_HASHES`), and compares them with `MessageDigest.isEqual`, which runs in constant time.
- **Alternatives:** Spring Security (the upgrade path), OAuth2 or JWT.
- **Why:** about 40 lines you can explain completely. SHA-256 is enough for long random keys; bcrypt is for human passwords.

## 10. Errors, validation, correlation ids, logs

- **Errors:** [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details with stable `type` URIs.
- **Validation:** Bean Validation on request records.
- **Correlation ids:**
  - Taken from `X-Correlation-Id`, else nginx's `X-Request-Id`, else a new UUID.
  - Only `[A-Za-z0-9-]{1,64}` is accepted, so a client cannot inject into the logs.
  - The id goes into the MDC, the response header, the transfer row, the outbox row, a Kafka header, and the consumer's MDC.
- **Logs:**
  - ECS JSON, with `instanceId` as the node name.
  - Never logged: keys, request bodies, SQL parameters.

## 11. Multiple instances

- **Choice:** nothing that affects correctness lives in JVM memory. Locks, idempotency and dedupe are all in Postgres.
- **Scheduled jobs** (outbox relay, key purge) take `pg_try_advisory_xact_lock(namespace, job)`, so one instance runs each at a time. The lock is released at commit, or when a crashed instance's connection goes away.
- **Flyway** already serializes migrations with its own advisory lock, so three instances can start at once.
- **Every response** carries `X-Served-By`, and every transfer stores `served_by`.

## 12. nginx in front of three instances (default D8)

- **Choice:**
  - **Balancing:** round robin, with passive health checks only (`max_fails=1 fail_timeout=5s`; active checks need NGINX Plus).
  - **DNS:** `resolve` with Docker's DNS, so one config works for 1 or 3 instances and picks up a restarted container's new IP.
  - **Keep-alive:** 30 s, shorter than Tomcat's 60 s.
  - **POST retries:** `proxy_next_upstream error timeout non_idempotent` on `/v1/**`.
  - **Edge rules:** paths containing `;` get 400. Everything under `/actuator` except `/actuator/health` gets 404. nginx is published on `127.0.0.1` only, because the demo key in `.env.example` is public.
- **Why the POST retries are safe:** nginx refuses to resend a POST by default, because it can't know whether that is safe. Ours is, because every `/v1` POST requires an Idempotency-Key. The demo routes have no key, so they are excluded.
- **Evidence:** [`results/phase-3-failover-run.md`](results/phase-3-failover-run.md). With app2 SIGKILLed mid-run, all 600 transfers got a final answer and **no client retries were needed**. `InstanceFailoverIT` also asserts, from nginx's JSON access log, that at least one `/v1/transfers` request was tried on two upstreams.

## 13. Kafka: key, partitions, consumer (default D7)

- **Topic:** `transfer.completed`, 6 partitions, keyed by the **sending** account. All of one sender's events stay in order, which is what the velocity rule counts.
- **Consumer group** `activity-feed`:
  - Each event is written to the `activity_feed` read model in one transaction.
  - `event_id` is unique, so redeliveries (at-least-once) become no-ops.
  - The offset is committed after the transaction.
- **Failures** (code review fix):
  - Spring Kafka's default handler retried a record 10 times with no pause, then skipped it and committed the offset. That is silent loss.
  - Now a `DefaultErrorHandler` retries transient failures (database down, lock timeouts) with exponential back-off, capped at 30 s, for as long as it takes. The partition waits rather than losing an event, and consumer lag in Grafana shows it.
  - Records that can never succeed (unreadable JSON, invalid data) go straight to `transfer.completed.DLT`.
  - The consumer reads events tolerantly (unknown fields ignored), so a newer producer can add fields during a rolling deploy.
- **Rules:**
  - `amount >= $1,000`.
  - More than 5 sends from one account within 60 s of event time.
  - They are monitoring, not blocking, and approximate.
- **Alternatives:** two events per transfer (one per account); keying by transfer id, which gives no per-account ordering.

## 14. Publish-after-commit, then the transactional outbox

- **Phase 4 (publish-after-commit):** a `@TransactionalEventListener(AFTER_COMMIT)` published the event. What can go wrong, plainly:
  1. A crash between commit and send loses the event.
  2. `send()` is asynchronous, so a record still buffered in the producer when the process dies is lost.
  3. If Kafka is down, the request thread blocks up to `max.block.ms` after the commit, then the event is lost.
  4. Spring logs any exception thrown after the commit and swallows it, so the client gets 201 while the event is gone.

  `PublishAfterCommitCrashIT` (tag `phase-4`) proved #1.
- **Phase 6 (outbox):** the event row is inserted in the **same transaction** as the entries. `OutboxRelay` publishes committed rows:
  - one instance at a time (advisory lock);
  - oldest first;
  - waiting for each acknowledgement;
  - stopping at the first failure, so a later event never overtakes an earlier one;
  - then setting `published_at`.

  Delivery is at-least-once, and the consumer drops duplicates. `OutboxCrashIT` shows the same crash now delivers the event exactly once to the feed.
- **Keeping the relay's transaction short** (code review fix):
  - Each acknowledged row's `published_at` is flushed immediately, not at commit, so the transaction never sits idle long enough for Postgres's 10 s `idle_in_transaction_session_timeout` to kill it. If it were killed, its advisory lock would be released while it was still sending, and a second relay could start.
  - A batch also stops starting new sends after 4 s.
- **Alternatives:**
  - Debezium/CDC (more moving parts).
  - Kafka transactions (they don't cover the database).
  - `SKIP LOCKED` with several relays (that can reorder events for one key).
- **Tuning:** the relay sends with `linger.ms=0`. Kafka 4 defaults to 5 ms, which with one-at-a-time acknowledgements capped the relay near the incoming rate.

## 15. Live dashboard feed

- **Choice:**
  - Each instance tails the shared `activity_feed` table every second and pushes new rows over SSE.
  - Ids are assigned at insert but become visible at commit, so a row with a smaller id can appear later. So every tick re-reads the 500 rows behind the newest one sent, and each subscriber remembers the last 2,000 ids it was sent. That fills such gaps without duplicates.
  - Reconnects resume with `Last-Event-ID`, **clamped** to the last 25 rows. An earlier version trusted any value, so one client sending `Last-Event-ID: 0` held back every other dashboard on that instance (code review fix).
  - Each subscriber has its own virtual thread and a bounded queue for socket writes. A browser that stops reading stalls only its own stream; if its queue fills, its stream is closed so it reconnects. The closing happens on that subscriber's thread, because `complete()` waits for the write that is stuck.
  - The browser reads the stream with `fetch()` plus a small parser, because `EventSource` cannot send the `X-API-Key` header.
  - A heartbeat every 15 s keeps nginx and the browser from closing an idle connection.
- **Alternatives:**
  - A per-instance Kafka consumer group (no rule flags, no resume across instances).
  - Postgres LISTEN/NOTIFY (needs a dedicated connection).
  - A cookie or a query-string key (CSRF risk, or keys in logs).

## 16. Demo buttons run server-side

- **Choice:** the two demos are endpoints that send real HTTP requests through nginx from 100 platform threads, behind a start gate.
- **Why:** a browser opens about 6 connections per host, so "100 concurrent" from a page would really be about 6 at a time. The demo endpoints have no Idempotency-Key, so nginx never retries them.

## 17. Observability

- **Metrics:** Micrometer exposes `/actuator/prometheus`, tagged `app_instance`. (Not `instance`: Prometheus already uses that label.)
- **Prometheus** finds the instances by DNS. In the lite profile, app2 and app3 don't resolve and simply produce no target.
- **Grafana** is provisioned, with anonymous read-only access.
- **Business counters:**
  - writes by operation and result;
  - lock timeouts by scope;
  - rule hits;
  - an outbox-backlog gauge. It is refreshed every 5 s in the background: a gauge that queried the database during the scrape would make all of an instance's metrics disappear while the database is down.

## 18. Testing strategy

- **Unit tests** cover pure logic.
- **In-process integration tests** run against Testcontainers Postgres and Kafka (one shared Spring context).
- **Cluster tests** start the **packaged jar** in containers behind the real `nginx.conf`, so they can SIGKILL processes.
- **The crash test pauses the app deterministically, with no sleeps:**
  1. The test holds `LOCK TABLE accounts IN SHARE MODE`. That still allows the app's row locks, inserts and foreign-key checks, but blocks the balance `UPDATE`, which comes last.
  2. The app stops with the transfer, both entries, the key and the outbox row written but not committed.
  3. The test checks this in `pg_locks`, then SIGKILLs the app.
- **Crash-test containers** get a 5-minute lock timeout, so the pause can only end with the kill.
- **The load test uses a constant arrival rate:** k6 starts 100 requests per second whether or not earlier ones have finished. With a fixed number of looping users, a slow response delays that user's next request, so the test sends less exactly when the server struggles and the tail looks better than it is (coordinated omission). Requests k6 could not start in time are reported as dropped iterations.

## 19. Zero budget and choice of tools

- **Everything runs locally** in Docker Compose: no cloud accounts and no paid services. The optional cloud VM (§23) uses only Oracle's Always Free tier.
- **Chart.js 4.5.1 is vendored** (MIT licence), so the dashboard makes no third-party requests.
- **Pinned versions** with notes:
  - `nginx:1.30.6-alpine`: the stable branch. `nginx:alpine` is mainline.
  - `prom/prometheus:v3.13.4`: LTS.
  - `grafana/grafana:13.2.4`: `grafana-oss` is stale.
  - `apache/kafka:4.3.1`, `postgres:18.6`, `ghcr.io/kafbat/kafka-ui:v1.5.0`, `grafana/k6:2.3.0`.
- **JUnit 6 (default D3):** managed by Boot 4.1. It is the same Jupiter API as JUnit 5.

## 20. Memory budget, JVM flags and the GC tuning it needed

- **Limits** (full profile):

  | Service | Memory limit | Settings |
  |---|---|---|
  | app × 3 | 768 MB each | 320 MB heap |
  | postgres | 768 MB | `shared_buffers=192MB` |
  | kafka | 768 MB | 384 MB heap |
  | kafbat-ui | 512 MB | |
  | grafana | 512 MB | |
  | prometheus | 256 MB | |
  | nginx | 64 MB | |
  | k6 | 512 MB | during the load test only |

  Measured usage during the load test is in `results/k6/*-docker-stats.txt`.
- **Tuning runs** (100 transfers/s for 60 s; every run, including later ones, is in [`results/k6/history.md`](results/k6/history.md)):

  | Run | Change | p99 | Max | Dropped iterations |
  |---|---|---|---|---|
  | `2026-10-16T14-24-25-685Z.json` | SerialGC, 96 MB code cache | 775.4 ms | 2198.6 ms | 66 |
  | `2026-10-16T14-27-24-462Z.json` | G1, 128 MB code cache | 440.1 ms | 712.3 ms | 8 |
  | `2026-10-16T14-30-48-313Z.json` | G1 + relay `linger.ms=0` | 467.8 ms | 880.2 ms | 17 |

  In the first run, Prometheus showed full GC pauses of 160–300 ms (code-cache and metaspace thresholds). While one instance is paused it keeps its row locks, so requests on the other two queue behind it: Hikari reported up to 16 pending connections.

  **Each change was measured with one run on a shared laptop.** Later, two runs of one commit, minutes apart, differed by about 2.5× at p99, and re-runs of `0c0586f` itself were much slower than the row above (see the history). So these rows record what was observed, not a proven improvement. The G1 change rests on the mechanism above; `linger.ms=0` fixed a relay that could not keep up with the incoming rate (§14), not latency.

  Those GC and Hikari figures were read live from Prometheus during the session and were **not** saved to a file. Since then, `scripts/load-test.sh` saves them for every run as `results/k6/<timestamp>-prometheus.txt`. All three runs above used Grafana with its earlier 384 MB limit.
- **Grafana:** given 512 MB, its documented minimum. At 384 MB it spent its time garbage collecting.

## 21. What the code review found and what changed

A multi-agent review of the finished code produced these findings. Each was checked against the code and then fixed:

| Finding | Fix |
|---|---|
| `/v1;x/accounts` and `/%761/accounts` skipped the API-key check (raw-URI prefix match) | Deny by default; check the decoded path Spring routes on; tests for both (§9) |
| `/actuator;x/prometheus` got past nginx's `/actuator/` block; nginx was published on all interfaces with a public demo key | `location ^~ /actuator`, `;` rejected, loopback-only port, `info` endpoint removed (§12) |
| The consumer silently dropped a record after 10 instant retries | Back-off retries for transient errors, dead-letter topic for poison records, tolerant reader (§13) |
| The relay's transaction could sit idle past 10 s and be killed mid-batch | Flush after every acknowledgement; 4 s batch budget (§14) |
| One reconnecting dashboard could starve the others; a stalled browser blocked the shared scheduler thread | Clamped resume point; per-subscriber virtual thread and queue; re-read window instead of a 1 s settle delay (§15) |
| The outbox gauge queried the database during every scrape | Background refresh (§17) |
| The insufficient-funds problem had two `status` members | The transfer's status is now `transferStatus`; the test asserts `status` is 422 |
| `correlation_id` could be changed while a transfer was `PENDING` | V6 migration adds it to the guard; new test for the `PENDING` branch |
| A NUL character in an account name caused a 500 | Validation rejects control characters (400) |
| Demo runs had no concurrency limit | One run per instance (429 `demo-busy`); demos are off unless enabled (compose enables them) |
| The failover test didn't check nginx's re-send; `smoke.sh` didn't check that 3 instances answered | Both assert it now |
| The before-locking evidence named a test that wasn't on the tag it cited | Tag `phase-1-concurrency-evidence` has phase-1 code plus that exact test, and the evidence was re-run there |
| The 201 body (and its stored replay) showed `createdAt` in nanoseconds; a GET of the same row showed microseconds | Entities take their timestamps at Postgres's precision (`DbTime`); a test compares the two |
| A pool timeout or lost database connection returned a generic 500 | `503 database-unavailable` + `Retry-After`, counted in `ledger_database_unavailable_total` (§6) |
| When a stalled browser's queue overflowed, closing its stream still blocked the scheduler thread | The subscriber's own thread completes the stream; a unit test reproduces the stall (§15) |

## 22. Who wrote the core logic (default D5)

You asked me to finish the project overnight, so I implemented `TransferService`, `IdempotencyService`/`RequestFingerprint` and `OutboxRelay` myself. Branch `practice/core-logic` has those three reset to stubs, with all their tests still in place, so you can write them yourself as your original plan intended, then compare with `main`.

## 23. Phase 8: one Always Free VM on Oracle Cloud

- **Choice:** Terraform (`infra/oci`) for one `VM.Standard.A1.Flex` with 2 Arm OCPUs and 12 GB, running the same `compose.yaml` stack as the laptop.
- **Alternatives:** other always-free VMs are far smaller (GCP's `e2-micro` has 1 GB, a fifth of the full profile's memory limits), and other providers' larger free VMs are time-limited trials. Managed Postgres and Kafka would be billed and would change what the tests prove.
- **Why it stays free:** Oracle's Always Free tier covers 1,500 OCPU hours and 9,000 GB hours of A1 a month, which it equates to 2 OCPUs and 12 GB. The VM uses exactly that, and nothing billed (no load balancer, NAT gateway, reserved IP or managed service). The variables refuse more than 2 OCPUs, 12 GB or 200 GB of disk.
  - **The allowance was halved in 2026** (from 4 OCPUs and 24 GB). The first version of this phase was written for 4/24; the review before anything was launched caught it against Oracle's current page, and no VM was ever created at 4/24.
  - **2/12 is enough:** the full profile's limits add up to about 5.2 GB, and the last laptop load test used about 3 GB (`results/k6/2026-10-16T19-40-53Z-docker-stats.txt`). Two OCPUs will give lower throughput than the 24-thread laptop; results from the VM say so in their hardware line.
- **Disk:** 100 GB, not the 50 GB minimum. Balanced block volumes (the boot volume default) get 60 IOPS and 480 KB/s per GB, so Postgres and Kafka get twice the IOPS, and the other 100 GB of the 200 GB allowance stays free for the two Always Free AMD micro VMs.
- **Only SSH is reachable, from one IP.** The demo API key in `.env.example` is public, so the API must never be. The dashboard, Grafana, Prometheus and kafbat-ui stay on the VM's loopback, as locally, and reach you through an SSH tunnel. The VCN's default security list (SSH from anywhere) is taken over with no rules.
- **Code over SSH, not `git clone`:** `deploy.sh` pipes `git archive` of a commit to the VM. It works while the repo is private (default D6; it is public since 2026-10-18), and it avoids putting a GitHub token or deploy key in user data or Terraform state. A redeploy is the same command, and an uncommitted change can't reach the VM by accident.
- **Secrets are made on the VM** by the first deploy (`openssl rand`, `scripts/new-api-key.sh`), written to a temporary file and moved into place only when complete, so they never pass through Terraform state, user data or the repo, and an interrupted first deploy can't leave the public demo key behind. A deploy refuses to start if `.env` accepts the public key.
- **The VM can't be replaced by accident:** a newer image, changed user data and a new SSH key are all ignored after creation (each would otherwise destroy the VM and its data). The booted image's name is kept from the creating apply, so a retired image can't break later plans.
- **Capacity:** free A1 capacity in Ashburn was sold out in every availability domain, at every size, on 2026-10-18. `capacity-check.sh` asks OCI's capacity report (read-only, free). `wait-and-apply.sh` waits for room and is meant to run unattended for days, so it was built against what went wrong while it was being written:
  - `oci.exe` ends its output with CRLF, so the first `capacity-check.sh` could never see `AVAILABLE` on Windows.
  - A background shell that was "killed" kept running underneath, and a second copy was started next to it: there were two loops sharing one state. The script now takes a lock, re-checks the state every round, never applies a plan that deletes or replaces anything, and will not launch while any A1 OCPU is in use anywhere in the tenancy or too little of the free 200 GB of storage is left (OCI's region-wide usage counters, which see every compartment). A stop request during the apply that creates the VM still records it.
  - bash reads a script while running it, so editing the file under a running loop could change what it does after the loop. The body now runs from a function that is fully read before it starts.
  - Network errors, rate limits and capacity mean "wait again"; only errors that waiting cannot fix (credentials, configuration, quotas) stop it. `LimitExceeded` stops it only if OCI's A1 limit really has no room, because OCI is known to return it now and then for A1 launches in tenancies running nothing.
  - Its safety checks fail closed: a function called from an `if` runs without `set -e`, so every step that matters is checked explicitly, and a plan is applied only if Terraform's own summary says `0 to destroy`.
  - Checked with a scenario harness that fakes `terraform` and `oci` (24 scenarios, 71 checks), three rounds of independent review, and `vm-deploy.sh` in an Ubuntu environment with real rsync and permissions.
- **Restarts:** Oracle may stop an Always Free VM that stays idle for 7 days (CPU, network and memory all below 20%). Starting it again needs free capacity, like a launch, so the README retries it; a systemd unit then starts the stack at boot. The VM is not kept busy artificially to dodge the policy.
