CREATE TABLE project_publications (
    project_id bigint PRIMARY KEY REFERENCES project_drafts(id) ON DELETE CASCADE,
    slug varchar(100) NOT NULL UNIQUE,
    revision varchar(36) NOT NULL UNIQUE,
    draft_version bigint NOT NULL CHECK (draft_version >= 0),
    payload text NOT NULL
);
