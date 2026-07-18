ALTER TABLE outbox_event
    ADD COLUMN claim_owner varchar(100),
    ADD COLUMN claim_until timestamptz;

CREATE INDEX ix_outbox_event_claim
    ON outbox_event (status, claim_until, next_attempt_at, created_at);
