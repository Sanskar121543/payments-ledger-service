# Crash tests (recorded 2026-10-16)

Command: `./mvnw verify`. Each test runs the packaged app in containers against Postgres 18.6 and Kafka 4.3.1.

## `TransferCrashIT`: app killed mid-transfer

1. The test holds `LOCK TABLE accounts IN SHARE MODE`.
2. app1 is sent a transfer.
3. Once app1 is waiting on its final `update accounts`, the test reads app1's granted write locks:

   ```
   app1 holds write locks on [idempotency_keys, ..., ledger_entries, ..., outbox_events, ..., transfers, ...] at the moment it is killed
   ```

   So app1 had already inserted the idempotency key, the transfer, both ledger entries and the outbox row.
4. app1 is SIGKILLed.

Assertions that passed:

- The client saw a dropped connection.
- Zero rows survived in `transfers`, `ledger_entries`, `idempotency_keys` and `outbox_events`.
- Balances are unchanged, and the ledger invariants hold.
- Retrying the same Idempotency-Key on app2 created exactly one transfer (`servedBy: app2`), and a second retry replayed identical bytes.

## `OutboxCrashIT`: crash after commit, before Kafka has the event

1. Kafka is paused, and a transfer commits: the API returns 201 without waiting for Kafka.
2. The outbox row is committed but not yet published.
3. Both app instances are SIGKILLed, and Kafka is unpaused.
4. A fresh instance (app3) starts.

Assertions that passed:

- The outbox row was published.
- The event is on the topic.
- The activity feed has exactly one row for the transfer: possible duplicates from at-least-once delivery are dropped by `event_id`.

The same scenario lost the event under publish-after-commit. The phase-4 test `PublishAfterCommitCrashIT` (see tag `phase-4`) proved that, and it was replaced by this test.
