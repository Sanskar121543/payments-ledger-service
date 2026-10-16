# Results

Recorded evidence. The README quotes only numbers from these files.

| File | What it records |
|---|---|
| [`phase-2-before-locking.md`](phase-2-before-locking.md) | The concurrency test run against the lock-free phase-1 code: server errors, overdraft through lost updates, deadlocks |
| [`phase-3-failover-run.md`](phase-3-failover-run.md) | One of three instances SIGKILLed during 600 transfers: no lost or duplicated transfers, no client retries needed |
| [`phase-6-crash-runs.md`](phase-6-crash-runs.md) | App killed mid-transfer (nothing half-written survives); crash after commit (the outbox still delivers the event) |
| [`k6/latest.md`](k6/latest.md), [`k6/latest.json`](k6/latest.json) | The most recent load test (copied into the README) |
| `k6/<timestamp>.json` | Every recorded load-test run, including k6's full summary, the hardware and the git commit |
| `k6/<timestamp>-docker-stats.txt` | Memory and CPU of every container, sampled during the run |
