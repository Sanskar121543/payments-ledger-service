# Instance failover run (recorded 2026-10-16)

Command: `./mvnw verify` (test `InstanceFailoverIT`). Topology: Postgres 18.6, app1–app3 (768 MB each), nginx 1.30.6 with `nginx/nginx.conf`.

What the test does:
1. Sends 600 transfers through nginx from 16 client threads, each with its own Idempotency-Key.
2. Retries a transfer with the same key on a dropped connection, 409, 502, 503 or 504.
3. SIGKILLs app2 after the 200th transfer has finished.

Logged by the test in this run:

```
SIGKILLed app2 after 200 finished transfers
finals={422=31, 201=569} clientRetries=0
```

- All 600 transfers ended with a final answer: 569 `201` and 31 `422` (insufficient funds).
- **0 client retries were needed.** nginx re-sent the requests that died with app2 to app1/app3. That is safe because every `/v1` POST has an Idempotency-Key (`proxy_next_upstream … non_idempotent`).

What the test asserted, and passed:

- Every key has exactly one stored response, and it equals what the client received.
- COMPLETED transfers = number of `201`s: none lost, none duplicated.
- Money is conserved across the 10 accounts, and the ledger invariants hold.
- app2 served transfers before the kill and none after.

Test duration in this run: 10.6 s. Machine: see `HARDWARE_NOTE` in the load-test results (same laptop).
