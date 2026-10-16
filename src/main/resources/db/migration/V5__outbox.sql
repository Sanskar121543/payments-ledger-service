-- Transactional outbox: written in the same transaction as the ledger entries, published to Kafka
-- afterwards by OutboxRelay. Published rows are kept (published_at set) for auditing.
CREATE TABLE outbox_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID        NOT NULL UNIQUE,
    topic          TEXT        NOT NULL,
    message_key    TEXT        NOT NULL,
    payload        TEXT        NOT NULL,
    correlation_id TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX outbox_events_unpublished_idx ON outbox_events (id) WHERE published_at IS NULL;
