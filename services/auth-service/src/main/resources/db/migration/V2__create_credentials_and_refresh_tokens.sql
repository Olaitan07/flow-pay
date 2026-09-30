CREATE TABLE credentials (
    customer_id     UUID         PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_credentials_email UNIQUE (email),
    CONSTRAINT ck_credentials_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_credentials_failed_attempts CHECK (failed_attempts >= 0)
);

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    customer_id UUID        NOT NULL REFERENCES credentials (customer_id),
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_customer ON refresh_tokens (customer_id);
