CREATE FUNCTION forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END
$$;

CREATE TABLE accounts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    type          TEXT        NOT NULL CHECK (type IN ('FUNDING', 'CUSTOMER')),
    currency      TEXT        NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    balance_cents BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The funding account is where all test money comes from, so it is the only account allowed below zero.
    CONSTRAINT no_customer_overdraft CHECK (type = 'FUNDING' OR balance_cents >= 0)
);

CREATE UNIQUE INDEX one_funding_account ON accounts (type) WHERE type = 'FUNDING';

INSERT INTO accounts (name, type) VALUES ('Funding', 'FUNDING');

CREATE TABLE transfers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    from_account_id BIGINT      NOT NULL REFERENCES accounts (id),
    to_account_id   BIGINT      NOT NULL REFERENCES accounts (id),
    amount_cents    BIGINT      NOT NULL CHECK (amount_cents > 0),
    failure_reason  TEXT,
    correlation_id  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_account_id <> to_account_id),
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);

CREATE INDEX transfers_from_account_idx ON transfers (from_account_id, id);
CREATE INDEX transfers_to_account_idx ON transfers (to_account_id, id);

CREATE FUNCTION guard_transfer_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'illegal transfer status change: % -> %', OLD.status, NEW.status;
    END IF;
    IF NEW.from_account_id <> OLD.from_account_id
        OR NEW.to_account_id <> OLD.to_account_id
        OR NEW.amount_cents <> OLD.amount_cents
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'only the status of a pending transfer may change';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER transfers_state_machine BEFORE UPDATE ON transfers
    FOR EACH ROW EXECUTE FUNCTION guard_transfer_update();
CREATE TRIGGER transfers_no_delete BEFORE DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER transfers_no_truncate BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TABLE ledger_entries (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  BIGINT      NOT NULL REFERENCES transfers (id),
    account_id   BIGINT      NOT NULL REFERENCES accounts (id),
    -- Signed: negative = money leaves the account (debit), positive = money arrives (credit).
    amount_cents BIGINT      NOT NULL CHECK (amount_cents <> 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transfer_id, account_id)
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id);

CREATE TRIGGER ledger_entries_no_update_delete BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
