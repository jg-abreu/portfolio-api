ALTER TABLE project_drafts
    ADD COLUMN role varchar(16) NOT NULL DEFAULT 'SECONDARY' CHECK (role IN ('FEATURED', 'SECONDARY', 'SIGNAL')),
    ADD COLUMN in_progress boolean NOT NULL DEFAULT false,
    ADD COLUMN cover_key varchar(68) NOT NULL DEFAULT '' CHECK (cover_key ~ '^(|[0-9a-f]{64}\.png)$'),
    ADD COLUMN profiles text NOT NULL DEFAULT '[]',
    ADD COLUMN en_cover_alt varchar(300) NOT NULL DEFAULT '',
    ADD COLUMN pt_cover_alt varchar(300) NOT NULL DEFAULT '';

ALTER TABLE project_publications
    ADD COLUMN cover_key varchar(68) NOT NULL DEFAULT '' CHECK (cover_key ~ '^(|[0-9a-f]{64}\.png)$');
