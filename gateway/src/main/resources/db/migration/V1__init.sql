-- Users & Auth
CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth_identities (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider     TEXT NOT NULL,
    provider_uid TEXT NOT NULL,
    credentials  JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_uid)
);

-- Audio
CREATE TABLE audio_meta (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id),
    original_name  TEXT NOT NULL,
    content_type   TEXT NOT NULL,
    size_bytes     BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audio_content (
    audio_id  UUID PRIMARY KEY REFERENCES audio_meta(id) ON DELETE CASCADE,
    data      BYTEA NOT NULL
);

-- Submissions
CREATE TABLE submissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id),
    audio_id     UUID NOT NULL REFERENCES audio_meta(id),
    model        TEXT NOT NULL DEFAULT 'fast',
    language     TEXT NOT NULL DEFAULT 'auto',
    diarize      BOOLEAN NOT NULL DEFAULT false,
    num_speakers INT,
    summary_mode TEXT NOT NULL DEFAULT 'off',
    status       TEXT NOT NULL DEFAULT 'pending',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_submissions_status ON submissions(status);

-- Stage results (business data)
CREATE TABLE transcripts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id    UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
    segments         JSONB,
    formatted_text   TEXT,
    audio_duration_s DOUBLE PRECISION,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE diarizations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id  UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
    segments       JSONB,
    formatted_text TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE summaries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
    text          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Stage operational data (retry tracking)
CREATE TABLE stage_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id   UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    stage           TEXT NOT NULL,
    attempt_num     INT NOT NULL,
    status          TEXT NOT NULL,
    external_job_id TEXT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error           TEXT,
    UNIQUE (submission_id, stage, attempt_num)
);

CREATE INDEX idx_stage_attempts_status ON stage_attempts(status, external_job_id);
