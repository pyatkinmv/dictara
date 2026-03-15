# Database Schema Design — Dictara

## Context

All job state in Dictara is currently in-memory and lost on restart. There are no user accounts, no job history, and no retry logic. This design introduces PostgreSQL as the persistence layer to support: multi-tenant user accounts (Telegram auth first, Google/password later), durable job history, per-stage retry with max 3 attempts, and reliable job dispatch via the outbox pattern.

---

## Schema

### Users & Auth

```sql
CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth_identities (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider     TEXT NOT NULL,     -- 'telegram' | 'password' | 'google'
    provider_uid TEXT NOT NULL,     -- telegram: chat_id | password: email | google: sub
    credentials  JSONB,             -- password: {hash: "$2b$..."} | telegram/google: null
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_uid)
);
```

**Auth flow (web app):**
- Telegram Login Widget on Flutter web app
- User clicks → approves in Telegram → Telegram sends signed payload to gateway
- Gateway verifies HMAC-SHA256 hash using bot token
- Upsert `auth_identities(provider='telegram', provider_uid=chat_id)` + create `users` row if new
- Issue JWT `{sub: user_id, exp: +30d}` → Flutter stores in localStorage
- All API requests carry `Authorization: Bearer <token>`
- tg-bot uses chat_id directly (no JWT)

**Future auth:** Google → `provider='google'`; password → `provider='password'`, `credentials={hash: bcrypt}`

---

### Audio

```sql
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
```

Bytes are separate from metadata so listing audio doesn't load content. `audio_content` can be replaced with a `storage_url` column when migrating to S3/MinIO.

One audio file can be reused across multiple submissions (e.g. retry with different params).

---

### Submissions

```sql
CREATE TABLE submissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id),
    audio_id     UUID NOT NULL REFERENCES audio_meta(id),
    model        TEXT NOT NULL DEFAULT 'fast',  -- stores user-facing alias ('fast'|'accurate'), not resolved name ('small'|'large-v3')
    language     TEXT NOT NULL DEFAULT 'auto',
    diarize      BOOLEAN NOT NULL DEFAULT false,
    num_speakers INT,
    summary_mode TEXT NOT NULL DEFAULT 'off',
    status       TEXT NOT NULL DEFAULT 'pending',  -- pending|processing|done|failed
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`status` is a denormalized summary for fast list queries. Source of truth is `stage_attempts`.

---

### Stage Business Data (results)

```sql
CREATE TABLE transcripts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id    UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
    segments         JSONB,            -- [{start, end, text}]  no speakers
    formatted_text   TEXT,
    audio_duration_s DOUBLE PRECISION,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE diarizations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id  UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
    segments       JSONB,             -- [{start, end, text, speaker}]
    formatted_text TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE summaries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
    text          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Client shows best available result: `diarizations.segments` if present, otherwise `transcripts.segments`.

---

### Stage Operational Data (retry tracking)

```sql
CREATE TABLE stage_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id   UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    stage           TEXT NOT NULL,    -- 'transcription' | 'diarization' | 'summarization'
    attempt_num     INT NOT NULL,
    status          TEXT NOT NULL,    -- processing|done|failed
    external_job_id TEXT,             -- transcriber job_id (transcription/diarization only)
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error           TEXT,
    UNIQUE (submission_id, stage, attempt_num)
);

-- Indexes for worker queries
CREATE INDEX ON submissions(status);
CREATE INDEX ON stage_attempts(status, external_job_id);
```

`external_job_id` enables crash recovery: on restart, worker resumes polling in-flight jobs instead of re-submitting.

---

## State Machine

### Stage pipeline

```
submission.status = 'pending'
  → worker picks up (SELECT FOR UPDATE SKIP LOCKED)
  → INSERT stage_attempts (stage='transcription', attempt=1, status='processing')
  → submit to transcriber, store external_job_id
  → poll until done/failed

transcription done:
  → INSERT transcripts row
  → if diarize=true  → INSERT stage_attempts (stage='diarization', attempt=1)
  → else if summary  → INSERT stage_attempts (stage='summarization', attempt=1)
  → else             → submission.status = 'done'

transcription failed, attempts < 3:
  → INSERT stage_attempts (stage='transcription', attempt=N+1)

transcription failed, attempts = 3:
  → submission.status = 'failed'

diarization done:
  → INSERT diarizations row
  → if summary_mode != 'off' → INSERT stage_attempts (stage='summarization', attempt=1)
  → else → submission.status = 'done'

diarization failed after 3 attempts:
  → proceed to summarization anyway (transcript is usable without speakers)

summarization done:
  → INSERT summaries row → submission.status = 'done'

summarization failed after 3 attempts:
  → submission.status = 'done'  ← still done, transcript visible
  → no summaries row inserted   ← client knows summary unavailable
```

### Crash recovery (on worker startup)

```sql
-- Resume in-flight transcription/diarization
SELECT * FROM stage_attempts
WHERE status = 'processing'
  AND external_job_id IS NOT NULL
FOR UPDATE SKIP LOCKED;
```
→ resume polling these jobs without re-submitting audio.

For `status='processing' AND external_job_id IS NULL` (summarization): re-queue if source row exists (`transcripts` or `diarizations`); if source row is missing, mark attempt as `failed`.

---

## Infrastructure

### PostgreSQL in docker-compose

```yaml
postgres:
  image: postgres:16
  environment:
    POSTGRES_DB: dictara
    POSTGRES_USER: dictara
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
  volumes:
    - postgres-data:/var/lib/postgresql/data
  ports:
    - "5432:5432"
```

Add `POSTGRES_PASSWORD=<secret>` to `.env` (gitignored, must be populated manually).

### Migrations

Flyway in the gateway module. Migration files in:
`gateway/src/main/resources/db/migration/V1__init.sql`

### Worker

Lives inside the gateway process (extends current `OrchestratorService`). Polls DB instead of managing only in-memory state. Picks up `submissions WHERE status='pending'` and resumes in-flight `stage_attempts` on startup.

---

## Files to create/change

| File | Change |
|------|--------|
| `docker-compose.yml` | Add `postgres` service + `postgres-data` volume |
| `.env` | Add `POSTGRES_PASSWORD` |
| `gateway/build.gradle.kts` | Add Spring Data JPA, PostgreSQL driver, Flyway |
| `gateway/src/main/resources/application.yaml` | Add datasource config |
| `gateway/src/main/resources/db/migration/V1__init.sql` | Full schema DDL |
| `gateway/src/main/kotlin/.../model/` | Replace in-memory models with JPA entities |
| `gateway/src/main/kotlin/.../service/OrchestratorService.kt` | DB-driven worker loop, persist stages |
| `gateway/src/main/kotlin/.../controller/TranscribeController.kt` | Accept user_id from JWT; update `GET /jobs/{jobId}` response DTO |
| `gateway/src/main/kotlin/.../auth/` (new) | JWT issuance + Telegram hash verification |
| `transcriber/app.py` + `transcriber/jobs.py` | Split into two endpoints (see below) |

---

## Transcriber API Split

Current single endpoint `POST /transcribe?diarize=true` becomes two:

**`POST /transcribe`** — transcription only (no speaker labels)
- Request: multipart `file`, query params `model`, `language`
- Response `202`: `{"job_id": "uuid"}`
- `GET /jobs/{job_id}` result shape unchanged: `{status, result: {segments: [{start, end, text}], audio_duration_s}}`

**`POST /diarize`** — speaker assignment for an already-transcribed audio
- Request: `multipart/form-data` with two parts:
  - `file` — the original audio bytes
  - `segments` — JSON string form-field: `[{"start":0.0,"end":2.5,"text":"Hello"}]`
- Query param: `num_speakers` (optional int)
- Response `202`: `{"job_id": "uuid"}`
- `GET /jobs/{job_id}` result shape: `{status, result: {segments: [{start, end, text, speaker}]}}`

Gateway `TranscriberClient` gains `submitDiarize(fileBytes, fileName, segments: List<Segment>, numSpeakers: Int?)`.
`TranscribeParams.diarize` field is **removed**. `TranscriberClient.submit` no longer appends `&diarize=...`.

**Note:** gateway re-uploads audio from `audio_content` for diarization. Transcriber-side caching is out of scope — accepted trade-off.

**Diarization alignment:** transcriber runs pyannote on raw audio, then merges speaker turns onto provided segment boundaries (`merge_diarization()` logic unchanged). Segments form-field provides word boundaries — without them, transcriber would need to re-run Whisper.

---

## Auth Middleware (Spring Security)

`OncePerRequestFilter` in `gateway/.../auth/JwtAuthFilter.kt`:
1. Reads `Authorization: Bearer <token>`
2. Validates signature + expiry with JWT secret
3. Extracts `user_id` (UUID) from `sub` claim
4. Sets `SecurityContextHolder`

Controllers receive user via `@AuthenticationPrincipal UUID userId`.

Public endpoints: `POST /auth/telegram`, `GET /health`.
All others require valid JWT.

**tg-bot path:** passes `X-Telegram-Chat-Id` + `X-Telegram-Display-Name`. Trust model: Docker internal network only (port not exposed externally). Gateway auto-creates `users` + `auth_identities` on first request per chat_id (UNIQUE constraint prevents duplicates; `display_name` written once, never updated).

---

## Model Layer Changes

**Delete:**
- `GatewayJob.kt`, `GatewayJobStatus.kt`, `JobResult.kt`, `ProgressInfo.kt`, `JobStore.kt`

**New JPA entities** (`gateway/.../model/`):
`UserEntity`, `AuthIdentityEntity`, `AudioMetaEntity`, `AudioContentEntity`, `SubmissionEntity`, `TranscriptEntity`, `DiarizationEntity`, `SummaryEntity`, `StageAttemptEntity`

**New repositories** (`JpaRepository<Entity, UUID>`):
`UserRepository`, `AuthIdentityRepository`, `AudioMetaRepository`, `AudioContentRepository`, `SubmissionRepository`, `TranscriptRepository`, `DiarizationRepository`, `SummaryRepository`, `StageAttemptRepository`

`Segment` data class is kept (JSONB serialization).

---

## GET /jobs/{jobId} Response Shape

| Field | Source after migration |
|-------|----------------------|
| `status` | `submissions.status` |
| `duration_s` | `submissions.updated_at - submissions.created_at` |
| `elapsed_s` | latest `stage_attempts.started_at` → now |
| `progress.phase` | latest `stage_attempts.stage` |
| `progress.processed_s` / `total_s` / `diarize_progress` | in-memory only (high-frequency, not persisted) |
| `result.segments` | `diarizations.segments` if present, else `transcripts.segments` |
| `result.formatted_text` | `diarizations.formatted_text` if present, else `transcripts.formatted_text` |
| `result.audio_duration_s` | `transcripts.audio_duration_s` |
| `result.summary` | `summaries.text` |
| `error` | latest failed `stage_attempts.error` |

---

## Summarization Input

When `diarize=true` and `summary_mode != 'off'`: summarizer receives `diarizations.formatted_text` (with speaker labels).
When `diarize=false`: summarizer receives `transcripts.formatted_text`.

---

## Worker Invariants

- Only the worker updates `submissions.status` — never the controller.
- Single-threaded. Both pickup and recovery queries use `SELECT ... FOR UPDATE SKIP LOCKED`.
- On startup:
  - `submissions WHERE status='pending'` → dispatch
  - `stage_attempts WHERE status='processing' AND external_job_id IS NOT NULL` → resume polling
  - `stage_attempts WHERE status='processing' AND external_job_id IS NULL` → re-queue summarization if source row exists, else mark failed

---

## Verification

1. `docker compose up postgres` → Flyway applies `V1__init.sql`
2. `POST /transcribe` → rows in `submissions` + `stage_attempts`
3. Kill gateway mid-job → restart → worker resumes via `external_job_id`
4. Transcriber crash × 3 → `submissions.status = 'failed'`
5. Summary fails × 3 → `submissions.status = 'done'`, no `summaries` row, transcript visible
6. Web: Telegram Login Widget → `users` + `auth_identities` created → JWT returned
7. tg-bot: send audio → `auth_identities` auto-created for chat_id → submission processed
