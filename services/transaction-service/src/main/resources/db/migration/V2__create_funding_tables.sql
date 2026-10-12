-- A funding operation: money a customer is adding to a wallet from an outside source.
-- It is created PENDING and only becomes SUCCESS after the payment is confirmed AND the wallet has been credited.
CREATE TABLE fundings (
    id                  UUID           PRIMARY KEY,
    reference           VARCHAR(40)    NOT NULL,
    customer_id         UUID           NOT NULL,
    wallet_id           UUID           NOT NULL,
    source              VARCHAR(20)    NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    provider_reference  VARCHAR(100),
    failure_reason      VARCHAR(500),
    review_code         VARCHAR(20),
    review_reason       VARCHAR(500),
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ux_fundings_reference UNIQUE (reference),
    CONSTRAINT ck_fundings_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_fundings_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'REQUIRES_REVIEW')),
    CONSTRAINT ck_fundings_source CHECK (source IN ('BANK_TRANSFER', 'CARD', 'EXTERNAL_PROVIDER')),
    CONSTRAINT ck_fundings_review_code CHECK (review_code IS NULL OR review_code IN ('AMOUNT_MISMATCH', 'CREDIT_REFUSED')),
    -- A review status always says why; no other status carries a review code.
    CONSTRAINT ck_fundings_review_consistent CHECK ((status = 'REQUIRES_REVIEW') = (review_code IS NOT NULL))
);
CREATE INDEX ix_fundings_customer ON fundings (customer_id, created_at DESC);
CREATE INDEX ix_fundings_status_updated ON fundings (status, updated_at);

-- Every state change, in order: who or what moved a funding, and the detail. Written once, never edited.
CREATE TABLE funding_status_changes (
    id           UUID         PRIMARY KEY,
    funding_id   UUID         NOT NULL REFERENCES fundings (id),
    from_status  VARCHAR(16),
    to_status    VARCHAR(16)  NOT NULL,
    actor        VARCHAR(100) NOT NULL,
    detail       VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_funding_status_changes_funding ON funding_status_changes (funding_id, created_at);
