# Before row locking: what broke (phase-1 code, recorded 2026-10-16)

Command: `./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConcurrencyIT`,
run against the phase-1 `TransferService` (tag `phase-1`), which reads accounts without locking them.

| Test | Expected | What actually happened |
|---|---|---|
| 100 concurrent transfers among 5 accounts | only `201` or `422` | `{500=22, 422=6, 201=72}`: 22 server errors |
| 10 concurrent $10 transfers out of a $50 account | 5 succeed, 5 fail | `{201=10}`: all 10 succeeded, so $50 more left than the account held (lost updates) |
| 100 opposing transfers A→B / B→A | all succeed | requests hung until the test client gave up (`HttpTimeoutException`) |

Database errors in the test log for that run: **195 × `deadlock detected`**, **215 × `canceling statement due to lock timeout`**.

Why it happened:

- **Lost updates.** Two transactions read the same balance, both pass the balance check, and both write `balance - amount`. One write overwrites the other, so money is created.
- **Deadlocks.** Hibernate updates the two account rows in the order the transfer loaded them. So A→B and B→A take the row locks in opposite orders.

The fix (phase 2) locks both rows with `SELECT … FOR NO KEY UPDATE` in ascending id order before reading the balance.
The same test then passes; see `ConcurrencyIT` at tag `phase-2`.
