CREATE TABLE visits
(
    ip_hash         bytea       NOT NULL,
    last_counted_at timestamptz NOT NULL,

    CONSTRAINT pk_visits PRIMARY KEY (ip_hash),
    CONSTRAINT ck_visits_ip_hash_length CHECK (octet_length(ip_hash) = 32)
);

CREATE TABLE visit_counter
(
    id    smallint NOT NULL,
    total bigint   NOT NULL DEFAULT 0,

    CONSTRAINT pk_visit_counter PRIMARY KEY (id),
    CONSTRAINT ck_visit_counter_single_row CHECK (id = 1),
    CONSTRAINT ck_visit_counter_total CHECK (total >= 0)
);

INSERT INTO visit_counter (id) VALUES (1);
