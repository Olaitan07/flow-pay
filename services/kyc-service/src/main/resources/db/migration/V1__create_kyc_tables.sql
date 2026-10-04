-- One row per verification attempt. Never deleted: a rejected attempt stays as history.
CREATE TABLE kyc_submissions (
    id                  UUID         PRIMARY KEY,
    customer_id         UUID         NOT NULL,
    tier                SMALLINT     NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    -- tier 1
    date_of_birth       DATE,
    address_line1       VARCHAR(150),
    address_line2       VARCHAR(150),
    city                VARCHAR(100),
    state               VARCHAR(100),
    postal_code         VARCHAR(20),
    address_country     VARCHAR(2),
    -- tier 2. The number is encrypted; the hash (keyed) only detects reuse; last4 is for masked display.
    id_type             VARCHAR(30),
    id_number_encrypted TEXT,
    id_number_hash      VARCHAR(64),
    id_number_last4     VARCHAR(4),
    submitted_at        TIMESTAMPTZ  NOT NULL,
    decided_at          TIMESTAMPTZ,
    decided_by          VARCHAR(100),
    decision_reason     VARCHAR(500),
    CONSTRAINT ck_kyc_submissions_tier CHECK (tier IN (1, 2)),
    CONSTRAINT ck_kyc_submissions_status CHECK (status IN ('PENDING', 'REQUIRES_REVIEW', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_kyc_submissions_tier1_data CHECK (
        tier <> 1 OR (date_of_birth IS NOT NULL AND address_line1 IS NOT NULL AND city IS NOT NULL
                      AND state IS NOT NULL AND postal_code IS NOT NULL AND address_country IS NOT NULL)),
    CONSTRAINT ck_kyc_submissions_tier2_data CHECK (
        tier <> 2 OR (id_type IS NOT NULL AND id_number_encrypted IS NOT NULL
                      AND id_number_hash IS NOT NULL AND id_number_last4 IS NOT NULL))
);

-- A customer can have only one open (awaiting review) and one verified submission per tier, even under concurrency.
CREATE UNIQUE INDEX uq_kyc_open_submission ON kyc_submissions (customer_id, tier)
    WHERE status IN ('PENDING', 'REQUIRES_REVIEW');
CREATE UNIQUE INDEX uq_kyc_verified_submission ON kyc_submissions (customer_id, tier)
    WHERE status = 'VERIFIED';
CREATE INDEX ix_kyc_submissions_customer ON kyc_submissions (customer_id, submitted_at DESC);
CREATE INDEX ix_kyc_submissions_queue ON kyc_submissions (status, submitted_at);
CREATE INDEX ix_kyc_submissions_id_hash ON kyc_submissions (id_number_hash) WHERE id_number_hash IS NOT NULL;

-- Append-only record of everything that happened to a submission, including who looked at sensitive data.
CREATE TABLE kyc_decisions (
    id            UUID         PRIMARY KEY,
    submission_id UUID         NOT NULL REFERENCES kyc_submissions (id),
    customer_id   UUID         NOT NULL,
    type          VARCHAR(30)  NOT NULL,
    actor         VARCHAR(100) NOT NULL,
    reason        VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_kyc_decisions_type CHECK (type IN ('SUBMITTED', 'FLAGGED', 'APPROVED', 'REJECTED', 'SENSITIVE_DATA_VIEWED'))
);
CREATE INDEX ix_kyc_decisions_submission ON kyc_decisions (submission_id, created_at);

-- Events waiting to be published to Kafka. Written in the same transaction as the decision, so a decision
-- can never be saved without its event (and vice versa).
CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
