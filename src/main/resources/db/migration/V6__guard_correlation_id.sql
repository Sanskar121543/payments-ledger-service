-- The correlation id is how a transfer is traced through logs and Kafka, so it may not change either.
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
        OR NEW.reverses_transfer_id IS DISTINCT FROM OLD.reverses_transfer_id
        OR NEW.served_by <> OLD.served_by
        OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'only the status of a pending transfer may change';
    END IF;
    RETURN NEW;
END
$$;
