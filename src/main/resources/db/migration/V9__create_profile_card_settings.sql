CREATE TABLE profile_card_settings (
    id smallint PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL DEFAULT 0,
    draft text,
    published text,
    published_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO profile_card_settings (id) VALUES (1);
