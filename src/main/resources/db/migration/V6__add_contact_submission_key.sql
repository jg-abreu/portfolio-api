ALTER TABLE contact_messages ADD COLUMN submission_key uuid;
CREATE UNIQUE INDEX ux_contact_messages_submission_key ON contact_messages (submission_key);
