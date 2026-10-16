| Metric | Value |
|---|---|
| Scenario | 100 transfers/s offered for 60s (after a 20 s warm-up that is not counted), constant arrival rate, through nginx to 3 instances |
| Transfer requests sent | 5935 |
| Completed (201) / refused for insufficient funds (422) | 5935 / 0 |
| Unexpected status codes (warm-up included) | 0 |
| Dropped iterations (k6 could not keep up) | 66 |
| Latency p50 / p90 / p95 / p99 | 9.3 ms / 60.1 ms / 182.5 ms / 775.4 ms |
| Latency max | 2198.6 ms |
| Ledger invariant after the run | holds (all entries sum to 0) |
| Money conserved across the 20 accounts | yes |
| Hardware | ASUS ROG Strix G713PV laptop, AMD Ryzen 9 7845HX (12 cores / 24 threads), 15.2 GB RAM, Windows 11 Home, Docker Desktop with WSL2 |
| Docker host | 24 CPUs, 7905939456 bytes RAM visible to Docker, Docker Desktop, engine 29.8.0 |
| Other load on the machine | 24 containers of an unrelated OpenTelemetry demo, including its own load generator, were running at the same time |
| Recorded | 2026-10-16T14:24:25.685Z, commit 9239a91 |
