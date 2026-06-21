CREATE TABLE job_runs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name      TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'running',
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    rows_affected INT,
    error         TEXT
);

CREATE INDEX idx_job_runs_name_started ON job_runs(job_name, started_at DESC);
