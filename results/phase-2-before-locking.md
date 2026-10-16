# Before row locking: what broke (single instance, recorded 2026-10-16)

## Reproduce it

Tag `phase-1-concurrency-evidence` is the phase-1 code, whose `TransferService` reads accounts without locking them, plus the `ConcurrencyIT` test exactly as committed in phase 2:

```bash
git checkout phase-1-concurrency-evidence
./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConcurrencyIT
```

The test is *expected* to fail there.

## Two runs

| Test | Expected | First run (test not yet committed) | Re-run at tag `phase-1-concurrency-evidence` (commit `96e782e`) |
|---|---|---|---|
| 100 concurrent transfers among 5 accounts | only `201` or `422` | `{500=22, 422=6, 201=72}` | `{500=30, 422=13, 201=57}` |
| 10 concurrent $10 transfers out of a $50 account | 5 succeed, 5 fail | `{201=10}`: all 10 succeeded | `{201=10}`: all 10 succeeded |
| 100 opposing transfers A→B / B→A | all succeed | hung until the client gave up (`HttpTimeoutException`) | same |
| `deadlock detected` / `canceling statement due to lock timeout` in the test log | 0 / 0 | 195 / 215 | 225 / 320 |

The counts vary from run to run because the interleaving does; the failure modes do not.

## Why it happened

- **Lost updates.** Two transactions read the same balance, both pass the balance check, and both write `balance - amount`. One write overwrites the other. Ten $10 transfers out of a $50 account all succeed, so $50 is created from nothing.
- **Deadlocks.** Hibernate updates the two account rows in the order the transfer loaded them. So A→B and B→A take the row locks in opposite orders.

The fix (phase 2): lock both rows with `SELECT … FOR NO KEY UPDATE` in ascending id order before reading the balance. The same test passes from tag `phase-2` on. That is single-instance; `MultiInstanceConcurrencyIT` checks the same property across three instances behind nginx.
