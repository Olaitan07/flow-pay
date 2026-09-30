-- Delivery log. The message body is never stored: it may contain one-time codes or reset links.
CREATE TABLE notifications (
    id         UUID         PRIMARY KEY,
    type       VARCHAR(40)  NOT NULL,
    channel    VARCHAR(20)  NOT NULL,
    recipient  VARCHAR(254) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('SENT', 'FAILED'))
);

CREATE INDEX ix_notifications_recipient ON notifications (recipient, created_at);
