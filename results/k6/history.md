# Load-test history

Every recorded run of `scripts/load-test.sh 100 60s` (100 transfers/s for 60 s after a 20 s warm-up, through nginx to
3 instances), generated from the JSON files in this directory. Each file also has the full k6 summary, the hardware and
what else was running. Latencies are in milliseconds.

| Run | Commit | Note | Sent | Unexpected statuses | Dropped iterations | p50 | p95 | p99 | Max | Invariant and money conserved |
|---|---|---|---|---|---|---|---|---|---|---|
| [`2026-10-16T14-24-25-685Z.json`](2026-10-16T14-24-25-685Z.json) | `9239a91` | SerialGC, 96 MB code cache | 5935 | 0 | 66 | 9.3 | 182.5 | 775.4 | 2198.6 | yes |
| [`2026-10-16T14-27-24-462Z.json`](2026-10-16T14-27-24-462Z.json) | `eefaa5e` | G1, 128 MB code cache | 5993 | 0 | 8 | 10.5 | 203.2 | 440.1 | 712.3 | yes |
| [`2026-10-16T14-30-48-313Z.json`](2026-10-16T14-30-48-313Z.json) | `0c0586f` | G1 + outbox relay linger.ms=0 | 5985 | 0 | 17 | 10.8 | 158.8 | 467.8 | 880.2 | yes |
| [`2026-10-16T19-18-14-340Z.json`](2026-10-16T19-18-14-340Z.json) | `ca7e15f` | After the first review fixes; stack just started | 5922 | 0 | 79 | 12.0 | 776.8 | 2175.6 | 3279.8 | yes |
| [`2026-10-16T19-22-10-872Z.json`](2026-10-16T19-22-10-872Z.json) | `ca7e15f` | Same commit, re-run on the warm stack | 5994 | 0 | 7 | 11.1 | 287.0 | 863.4 | 1524.4 | yes |
| [`2026-10-16T19-25-52-236Z.json`](2026-10-16T19-25-52-236Z.json) | `0c0586f` | A/B: pre-review commit; stack just started, data wiped | 5565 | 93 | 444 | 305.0 | 4142.6 | 5425.2 | 8790.5 | yes |
| [`2026-10-16T19-28-24-631Z.json`](2026-10-16T19-28-24-631Z.json) | `0c0586f-dirty` | A/B: pre-review commit, re-run on the warm stack (see note 2) | 5863 | 0 | 138 | 16.4 | 929.3 | 1920.1 | 3562.7 | yes |
| [`2026-10-16T19-40-18-055Z.json`](2026-10-16T19-40-18-055Z.json) | `1e70946` | Final code; stack just started | 6001 | 0 | 0 | 10.4 | 225.1 | 457.0 | 731.1 | yes |
| [`2026-10-16T19-42-22-831Z.json`](2026-10-16T19-42-22-831Z.json) | `1e70946` | Final code, re-run on the warm stack (in the README) | 6001 | 0 | 0 | 9.7 | 165.2 | 427.5 | 874.5 | yes |

## Notes

1. **Every run shared the laptop** with the 24 containers of an unrelated OpenTelemetry demo, including its own load
   generator, so the tail latencies are noisy. Runs of the same commit minutes apart differ several-fold at p95/p99.
   Compare rows of one commit before reading anything into a change between commits.
2. **Why `0c0586f-dirty`:** the load-test script at that commit marked the tree dirty if *any* file differed, and the
   run before it had just rewritten `results/k6/latest.*`. The code was unchanged. The script now ignores `results/`.
3. **The two `0c0586f` re-runs** were made to check whether the first review fixes (`ca7e15f`) had slowed the
   service down. The older code was as slow or slower on the machine at that time, so the change in its numbers since
   14:30 came from the machine, not the code.
4. **Unexpected statuses** are counted in total only; the k6 summary does not say which codes they were.
5. **Cold versus warm.** In all three pairs, the first run started right after the stack itself started (the script
   builds and starts it), and the re-run reused the same, already warm, JVMs. The first run was slower each time. The
   20 s warm-up may not be enough for freshly started JVMs; this is an observation from three pairs, not a measured
   cause.
6. Runs from `ca7e15f` on also have a `<timestamp>-prometheus.txt` with GC pauses, connection-pool waits, the outbox
   backlog and lock timeouts for the run.
