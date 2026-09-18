CREATE TABLE scraping_sources
(
    source          VARCHAR(32) PRIMARY KEY,
    enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    next_run_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_success_at TIMESTAMPTZ,
    last_error_at   TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    runs_started    BIGINT      NOT NULL DEFAULT 0,
    runs_succeeded  BIGINT      NOT NULL DEFAULT 0,
    runs_failed     BIGINT      NOT NULL DEFAULT 0,
    fetched_count   BIGINT      NOT NULL DEFAULT 0,
    accepted_count  BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scraping_source CHECK (
        source IN ('djinni', 'dou', 'euremotejobs', 'justjoinit', 'landingjobs', 'linkedin', 'nofluffjobs', 'web3career')
    ),
    CONSTRAINT ck_scraping_source_counters CHECK (
        runs_started >= 0 AND runs_succeeded >= 0 AND runs_failed >= 0
        AND fetched_count >= 0 AND accepted_count >= 0
    )
);

INSERT INTO scraping_sources (source)
VALUES ('djinni'), ('dou'), ('euremotejobs'), ('justjoinit'), ('landingjobs'), ('linkedin'), ('nofluffjobs'), ('web3career');

CREATE TABLE scraping_runs
(
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source               VARCHAR(32)  NOT NULL REFERENCES scraping_sources (source),
    status               VARCHAR(16)  NOT NULL,
    criteria_categories  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    criteria_locations   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    criteria_remote_only BOOLEAN      NOT NULL,
    checkpoint           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    since_at              TIMESTAMPTZ,
    started_at            TIMESTAMPTZ NOT NULL,
    next_attempt_at       TIMESTAMPTZ NOT NULL,
    attempt_count         INTEGER     NOT NULL DEFAULT 0,
    lease_worker_id       VARCHAR(128),
    lease_token           UUID,
    lease_expires_at      TIMESTAMPTZ,
    fetched_count         BIGINT      NOT NULL DEFAULT 0,
    accepted_count        BIGINT      NOT NULL DEFAULT 0,
    failure_code          VARCHAR(64),
    completed_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scraping_run_status CHECK (status IN ('ACTIVE', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_scraping_run_attempts CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT ck_scraping_run_counters CHECK (fetched_count >= 0 AND accepted_count >= 0)
);

CREATE UNIQUE INDEX uk_scraping_run_active_source
    ON scraping_runs (source) WHERE status = 'ACTIVE';
CREATE INDEX idx_scraping_run_source_started
    ON scraping_runs (source, started_at DESC);
CREATE INDEX idx_scraping_run_retention
    ON scraping_runs (completed_at) WHERE status IN ('SUCCEEDED', 'FAILED');

CREATE TABLE scraping_batches
(
    batch_id        UUID PRIMARY KEY,
    run_id          UUID         NOT NULL REFERENCES scraping_runs (id) ON DELETE CASCADE,
    request_hash    VARCHAR(64)  NOT NULL,
    checkpoint      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    fetched_count   INTEGER      NOT NULL,
    accepted_count  INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_scraping_batch_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_scraping_batch_counters CHECK (fetched_count >= 0 AND accepted_count >= 0)
);

CREATE INDEX idx_scraping_batch_run_created ON scraping_batches (run_id, created_at);
