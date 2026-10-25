CREATE TABLE contact_messages
(
    id              bigint GENERATED ALWAYS AS IDENTITY,
    name            text        NOT NULL,
    email           text        NOT NULL,
    message         text        NOT NULL,
    status          text        NOT NULL DEFAULT 'pending',
    attempts        integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    CONSTRAINT pk_contact_messages PRIMARY KEY (id),
    CONSTRAINT ck_contact_messages_name_length CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_contact_messages_email_length CHECK (char_length(email) <= 254),
    CONSTRAINT ck_contact_messages_message_length CHECK (char_length(message) BETWEEN 10 AND 5000),
    CONSTRAINT ck_contact_messages_status CHECK (status IN ('pending', 'sending', 'sent', 'failed')),
    CONSTRAINT ck_contact_messages_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_contact_messages_sent_at CHECK ((status = 'sent') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_contact_messages_due
    ON contact_messages (next_attempt_at) WHERE status IN ('pending', 'sending');
