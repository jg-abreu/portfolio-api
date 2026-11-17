ALTER TABLE recommendation_sections
    DROP CONSTRAINT IF EXISTS recommendation_sections_kind_check,
    ADD CONSTRAINT recommendation_sections_kind_check CHECK (kind IN ('BOOKS', 'ITEMS', 'LINK')),
    ADD COLUMN slug VARCHAR(60),
    ADD COLUMN url VARCHAR(1000) NOT NULL DEFAULT '',
    ADD COLUMN en_description VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN pt_description VARCHAR(200) NOT NULL DEFAULT '';

WITH base AS (
    SELECT id, COALESCE(NULLIF(trim(BOTH '-' FROM regexp_replace(lower(en_title), '[^a-z0-9]+', '-', 'g')), ''), 'section') AS value
    FROM recommendation_sections
), ranked AS (
    SELECT id, value, row_number() OVER (PARTITION BY value ORDER BY id) AS position FROM base
)
UPDATE recommendation_sections s
SET slug = left(CASE WHEN r.position = 1 THEN r.value ELSE r.value || '-' || s.id END, 60)
FROM ranked r WHERE r.id = s.id;

ALTER TABLE recommendation_sections
    ALTER COLUMN slug SET NOT NULL,
    ADD CONSTRAINT recommendation_sections_slug_key UNIQUE (slug),
    ADD CONSTRAINT recommendation_sections_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
