CREATE TABLE idempotency_keys (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_id       TEXT        NOT NULL,
    idem_key        TEXT        NOT NULL CHECK (length(idem_key) BETWEEN 1 AND 255),
    request_hash    TEXT        NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    UNIQUE (client_id, idem_key),
    CHECK ((response_status IS NULL) = (completed_at IS NULL))
);

CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);

ALTER TABLE transfers
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'TRANSFER' CHECK (kind IN ('TRANSFER', 'REVERSAL')),
    ADD COLUMN reverses_transfer_id BIGINT REFERENCES transfers (id),
    ADD CONSTRAINT reversal_points_at_original CHECK ((kind = 'REVERSAL') = (reverses_transfer_id IS NOT NULL));

CREATE UNIQUE INDEX one_completed_reversal_per_transfer ON transfers (reverses_transfer_id)
    WHERE status = 'COMPLETED';

CREATE OR REPLACE FUNCTION guard_transfer_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'illegal transfer status change: % -> %', OLD.status, NEW.status;
    END IF;
    IF NEW.from_account_id <> OLD.from_account_id
        OR NEW.to_account_id <> OLD.to_account_id
        OR NEW.amount_cents <> OLD.amount_cents
        OR NEW.created_at <> OLD.created_at
        OR NEW.kind <> OLD.kind
        OR NEW.reverses_transfer_id IS DISTINCT FROM OLD.reverses_transfer_id THEN
        RAISE EXCEPTION 'only the status of a pending transfer may change';
    END IF;
    RETURN NEW;
END
$$;
