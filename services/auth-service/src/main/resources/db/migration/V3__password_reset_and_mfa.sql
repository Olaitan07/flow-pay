ALTER TABLE credentials ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- One-time password reset links. Only a hash of the token is stored.
CREATE TABLE password_reset_tokens (
    id          UUID        PRIMARY KEY,
    customer_id UUID        NOT NULL REFERENCES credentials (customer_id),
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_password_reset_tokens_customer ON password_reset_tokens (customer_id);

-- Short-lived email one-time codes: for the second step of login and for turning MFA on.
CREATE TABLE otp_challenges (
    id          UUID        PRIMARY KEY,
    customer_id UUID        NOT NULL REFERENCES credentials (customer_id),
    purpose     VARCHAR(20) NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_otp_challenges_purpose CHECK (purpose IN ('LOGIN', 'ENABLE_MFA')),
    CONSTRAINT ck_otp_challenges_attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_otp_challenges_customer ON otp_challenges (customer_id, purpose);
