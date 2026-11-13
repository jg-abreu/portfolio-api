CREATE TABLE publication_requests (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    requested_generation BIGINT NOT NULL DEFAULT 0 CHECK (requested_generation >= 0),
    dispatched_generation BIGINT NOT NULL DEFAULT 0 CHECK (dispatched_generation >= 0 AND dispatched_generation <= requested_generation),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_dispatched_at TIMESTAMPTZ,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
INSERT INTO publication_requests(id) VALUES (1);
