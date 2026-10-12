-- Wallets hold a customer's money in one currency. Amounts are NUMERIC, never floating point.
-- The CHECK constraints make a negative or overspent balance impossible even if application code is wrong.
CREATE TABLE wallets (
    id                 UUID           PRIMARY KEY,
    customer_id        UUID           NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    ledger_balance     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    available_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status             VARCHAR(10)    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_wallets_ledger_not_negative CHECK (ledger_balance >= 0),
    CONSTRAINT ck_wallets_available_not_negative CHECK (available_balance >= 0),
    CONSTRAINT ck_wallets_available_within_ledger CHECK (available_balance <= ledger_balance)
);

-- One live wallet per customer per currency. A closed wallet does not block opening a new one.
CREATE UNIQUE INDEX ux_wallets_customer_currency_live ON wallets (customer_id, currency) WHERE status <> 'CLOSED';
CREATE INDEX ix_wallets_customer ON wallets (customer_id);

-- Every balance change, with a caller-supplied unique reference so a retry never moves money twice.
CREATE TABLE wallet_movements (
    id                       UUID           PRIMARY KEY,
    reference                VARCHAR(100)   NOT NULL,
    wallet_id                UUID           NOT NULL REFERENCES wallets (id),
    type                     VARCHAR(6)     NOT NULL,
    amount                   NUMERIC(19, 2) NOT NULL,
    currency                 VARCHAR(3)     NOT NULL,
    ledger_balance_after     NUMERIC(19, 2) NOT NULL,
    available_balance_after  NUMERIC(19, 2) NOT NULL,
    created_at               TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ux_wallet_movements_reference UNIQUE (reference),
    CONSTRAINT ck_wallet_movements_type CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_wallet_movements_amount_positive CHECK (amount > 0)
);
CREATE INDEX ix_wallet_movements_wallet ON wallet_movements (wallet_id, created_at);

-- Who changed a wallet's status, from what, to what, and why.
CREATE TABLE wallet_status_changes (
    id           UUID         PRIMARY KEY,
    wallet_id    UUID         NOT NULL REFERENCES wallets (id),
    from_status  VARCHAR(10)  NOT NULL,
    to_status    VARCHAR(10)  NOT NULL,
    reason       VARCHAR(500) NOT NULL,
    actor        VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_wallet_status_changes_wallet ON wallet_status_changes (wallet_id, created_at);
