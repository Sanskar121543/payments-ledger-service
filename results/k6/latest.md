| Metric | Value |
|---|---|
| Scenario | 100 transfers/s offered for 60s (after a 20 s warm-up that is not counted), constant arrival rate, through nginx to 3 instances |
| Transfer requests sent | 6001 |
| Completed (201) / refused for insufficient funds (422) | 6001 / 0 |
| Unexpected status codes (warm-up included) | 0 |
| Dropped iterations (not sent: every virtual user was still waiting on a slow response; not in the latency figures) | 0 |
| Latency p50 / p90 / p95 / p99 | 9.7 ms / 50.5 ms / 165.2 ms / 427.5 ms |
| Latency max | 874.5 ms |
| Ledger invariant after the run | holds (all entries sum to 0) |
| Money conserved across the 20 accounts | yes |
| Hardware | ASUS ROG Strix G713PV laptop, AMD Ryzen 9 7845HX (12 cores / 24 threads), 15.2 GB RAM, Windows 11 Home, Docker Desktop with WSL2 |
| Docker host | 24 CPUs, 7905939456 bytes RAM visible to Docker, Docker Desktop, engine 29.8.0 |
| Other load on the machine | 24 containers of an unrelated OpenTelemetry demo, including its own load generator, were running at the same time |
| Recorded | 2026-10-16T19:42:22.831Z, commit 1e70946 |
