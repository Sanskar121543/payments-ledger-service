# Practice: write the core logic yourself

This branch is `main` with the four pieces of core logic replaced by stubs that throw
`UnsupportedOperationException`. Everything else is unchanged: the entities, the repositories, the controllers, the
migrations and **all of the tests**. When the tests pass, your version does what `main` does.

| Order | File | What to write | Tests that check it |
|---|---|---|---|
| 1 | `idempotency/RequestFingerprint.java` | `of(...)`: a SHA-256 over method, path and fields that cannot collide by moving characters between parts | `RequestFingerprintTest` |
| 2 | `idempotency/IdempotencyService.java` | `claim(...)` and `execute(...)` | `IdempotencyIT` |
| 3 | `transfer/TransferService.java` | `transfer`, `reverse`, the ordered row locking and `lockOrder` | `TransferIT`, `ReversalIT`, `ConcurrencyIT`, `LockOrderTest` |
| 4 | `outbox/OutboxRelay.java` | `relayBatch()` | `OutboxRelayTest`, `OutboxIT`, `OutboxCrashIT` |

Each stub's comment lists the rules it has to follow. DESIGN.md explains why each rule exists (sections 6, 7, 8 and 14).

## The loop

```bash
git switch practice/core-logic
./mvnw test -Dtest=RequestFingerprintTest                                          # unit tests, seconds
./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=IdempotencyIT   # one integration test
./mvnw verify                                                                     # everything, before you call it done
```

Until `OutboxRelay` is written, the app logs an `UnsupportedOperationException` from the relay every 250 ms. That is expected.

Integration tests start their own Postgres and Kafka in Docker. Stop the compose stack first
(`docker compose --profile full down`) so they have the memory.

## When you are stuck

- Re-read the stub's comment and the test that fails. The test name says what it expects.
- `git diff practice/core-logic main -- src/main/java/.../TransferService.java` shows the reference version. Try
  for a while before you look: the point is to be able to explain every line in an interview.
- `ConcurrencyIT` failing with deadlocks or `500`s means the locks are not taken in a consistent order.
  `results/phase-2-before-locking.md` shows what that looks like.

Commit your work to this branch (or a branch of your own made from it). `main` stays the reference.
