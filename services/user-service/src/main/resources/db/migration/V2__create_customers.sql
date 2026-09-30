CREATE TABLE customers (
    id            UUID         PRIMARY KEY,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(254) NOT NULL,
    phone_number  VARCHAR(16)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    country       VARCHAR(2)   NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_customers_email UNIQUE (email),
    CONSTRAINT uq_customers_phone_number UNIQUE (phone_number),
    CONSTRAINT ck_customers_status CHECK (status IN
        ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_customers_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_customers_country CHECK (country ~ '^[A-Z]{2}$')
);
