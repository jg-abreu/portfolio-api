ALTER TABLE contact_messages
    ADD COLUMN read_at timestamptz,
    ADD COLUMN archived_at timestamptz,
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

CREATE INDEX ix_contact_messages_inbox
    ON contact_messages (created_at DESC, id DESC) WHERE archived_at IS NULL;
CREATE INDEX ix_contact_messages_archived
    ON contact_messages (created_at DESC, id DESC) WHERE archived_at IS NOT NULL;
