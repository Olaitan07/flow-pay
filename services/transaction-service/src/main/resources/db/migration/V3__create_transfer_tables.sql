-- A person-to-person transfer. It is recorded as PROCESSING before any money moves, so a crash can never lose track
-- of a transfer, and it only becomes SUCCESS once the wallet service confirms the money moved.
-- amount, fee and total are frozen here at the time of the transfer: a later fee change never rewrites history.
CREATE TABLE transfers (
    id                    UUID           PRIMARY KEY,
    reference             VARCHAR(40)    NOT NULL,
    sender_customer_id    UUID           NOT NULL,
    recipient_customer_id UUID           NOT NULL,
    sender_wallet_id      UUID           NOT NULL,
    recipient_wallet_id   UUID           NOT NULL,
    amount                NUMERIC(19, 2) NOT NULL,
    fee                   NUMERIC(19, 2) NOT NULL,
    total                 NUMERIC(19, 2) NOT NULL,
    currency              VARCHAR(3)     NOT NULL,
    narration             VARCHAR(140),
    status                VARCHAR(12)    NOT NULL,
    failure_code          VARCHAR(50),
    failure_reason        VARCHAR(500),
    -- The customer's retry key and a fingerprint of what they asked for, so a retry is recognised and a reused key
    -- with different details is refused.
    idempotency_key       VARCHAR(100)   NOT NULL,
    request_hash          VARCHAR(64)    NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ux_transfers_reference UNIQUE (reference),
    CONSTRAINT ux_transfers_sender_key UNIQUE (sender_customer_id, idempotency_key),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfers_fee_not_negative CHECK (fee >= 0),
    CONSTRAINT ck_transfers_total CHECK (total = amount + fee),
    CONSTRAINT ck_transfers_not_to_self CHECK (sender_customer_id <> recipient_customer_id),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PROCESSING', 'SUCCESS', 'FAILED')),
    -- A failed transfer always says why; no other status carries a failure.
    CONSTRAINT ck_transfers_failure_consistent CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);
CREATE INDEX ix_transfers_sender ON transfers (sender_customer_id, created_at DESC);
CREATE INDEX ix_transfers_recipient ON transfers (recipient_customer_id, created_at DESC);
CREATE INDEX ix_transfers_status_updated ON transfers (status, updated_at);

-- Every state change, in order. Written once, never edited.
CREATE TABLE transfer_status_changes (
    id           UUID         PRIMARY KEY,
    transfer_id  UUID         NOT NULL REFERENCES transfers (id),
    from_status  VARCHAR(12),
    to_status    VARCHAR(12)  NOT NULL,
    actor        VARCHAR(100) NOT NULL,
    detail       VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_transfer_status_changes_transfer ON transfer_status_changes (transfer_id, created_at);
