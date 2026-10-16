-- The activity feed is the Kafka consumer's read model. It is conceptually another service's data,
-- so it has no foreign keys into the ledger tables.
CREATE TABLE activity_feed (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    transfer_id     BIGINT      NOT NULL,
    kind            TEXT        NOT NULL,
    from_account_id BIGINT      NOT NULL,
    to_account_id   BIGINT      NOT NULL,
    amount_cents    BIGINT      NOT NULL,
    served_by       TEXT,
    consumed_by     TEXT        NOT NULL,
    correlation_id  TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    large_amount    BOOLEAN     NOT NULL,
    high_velocity   BOOLEAN     NOT NULL
);

CREATE INDEX activity_feed_sender_time_idx ON activity_feed (from_account_id, occurred_at);
