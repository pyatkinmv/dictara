# gateway — Kotlin Spring Boot API

Central HTTP entry point: auth, job persistence, orchestration.

## Architecture

```
Client (tg-bot / Flutter)  ──HTTP──>  TranscribeController
                                       - saves audio: GCS (AudioStorageClient) or BLOB (audio_content)
                                             ↓
                                       OrchestratorService
                                       - single dispatchExecutor thread
                                       - dispatches one job at a time
                                             ↓
                              TranscriberClient  ──HTTP──>  transcriber
                              (submit by reference or bytes, + poll)
                                             ↓
                                       SummarizerPort (Gemini or Noop)
```

### Audio storage: GCS reference vs in-DB BLOB

Cloud Run enforces a hard 32 MiB HTTP request body limit, so streaming large
audio files directly to the transcriber returns `413`. When
`dictara.storage.gcs.bucket` (`GCS_UPLOADS_BUCKET`) is configured,
`AudioStorageClient` uploads the file to that bucket and the gateway submits the
job by `gs://` reference (`TranscriberClient.submitByReference`,
`audio_meta.storage_uri`) — the transcriber downloads it directly from GCS,
bypassing the HTTP body entirely. Uploaded objects are not deleted by the
application; a 90-day bucket lifecycle rule configured on the GCS bucket expires
them automatically (see [docs/deployment.md](../docs/deployment.md)).

When the bucket is not configured (e.g. local docker-compose), the gateway falls
back to the legacy path: the file is stored as a BLOB in `audio_content` and
streamed to the transcriber over multipart HTTP (`TranscriberClient.submit`).
Both paths are fully supported side by side — `audio_meta.storage_uri` is `NULL`
for BLOB-backed rows and a `gs://...` URI for GCS-backed ones.

## Submission status lifecycle

```
pending  →  processing  →  summarizing  →  done
   ↘             ↘               ↘
                failed          done (transcript usable even if summary failed)
```

| Status | Meaning |
|--------|---------|
| `pending` | Waiting in queue — not yet sent to transcriber |
| `processing` | Transcriber is actively transcribing this job |
| `summarizing` | Transcription done, summarizer running in parallel with next transcription job |
| `done` | Complete (transcript + optional summary available) |
| `failed` | Terminal failure (non-retryable transcriber error or 3 attempts exhausted) |

DB invariant: at most one submission can be `processing` at a time (enforced by partial unique index `idx_one_processing_submission`).

## Queue position

`GET /jobs/{id}` returns `queue_position` for `pending` submissions only:

```
queue_position = COUNT(pending submissions with createdAt < this.createdAt) + 1
```

Returns `null` for `processing`, `summarizing`, `done`, `failed`.

## Scheduled maintenance jobs

Two background jobs run automatically to keep GCS storage lean:

| Job | Schedule | What it does |
|-----|----------|--------------|
| `dedup_storage_uris` | Daily at 03:00 | Finds `audio_meta` rows with the same `content_hash` and updates them all to share the oldest `storage_uri`, making duplicate GCS objects unreferenced |
| `cleanup_orphaned_gcs_objects` | Weekly Sunday at 04:00 | Lists all objects in the GCS bucket, deletes any not referenced by `audio_meta.storage_uri`. Skips objects younger than 1 hour (grace period to avoid race with in-flight uploads) |
| `daily_db_backup` | Daily at 16:45 UTC | Queries all tables from `information_schema` (excluding `flyway_schema_history`), serializes each to gzipped JSON, uploads to `gs://{bucket}/backups/{date}/{table}.json.gz`. New tables added via Flyway are picked up automatically. **If the database grows large, this approach loads entire tables into memory and must be replaced with a streaming/pg_dump-based solution.** |

Every run is recorded in the `job_runs` table (`status`: `running` → `completed`/`failed`, `rows_affected`, `finished_at`). Use `JobTracker.tracked(name) { ... }` to wrap any future job in the same way — one line, no boilerplate.

## Key files

| File | Purpose |
|------|---------|
| `OrchestratorService.kt` | Job lifecycle: dispatch, transcription polling, summarization |
| `SubmissionStateService.kt` | DB state transitions (all `@Transactional`) |
| `TranscribeController.kt` | POST /transcribe, GET /jobs/{id} |
| `ExportController.kt` | GET /export — streams ZIP of all user transcriptions (transcript + summary + optional audio) |
| `TranscriberClient.kt` | HTTP client for transcriber service |
| `AudioStorageClient.kt` | Uploads audio to GCS for the reference-based submit path (active only when `dictara.storage.gcs.bucket` is set) |
| `job/JobTracker.kt` | Wraps any block in a `job_runs` DB record (running → completed/failed) |
| `job/StorageMaintenanceService.kt` | Two `@Scheduled` GCS maintenance jobs |
| `db/migration/` | Flyway migrations |

## Build

```bash
docker compose build gateway && docker compose up -d gateway
```

## Running integration tests locally

In CI, tests use Testcontainers (spins up a throwaway postgres). Locally, Testcontainers doesn't work (Docker Desktop API version mismatch), so tests connect to a real postgres container instead.

**One-time setup:**

1. Start a dedicated local postgres for tests (separate from the compose postgres, different port so it can't accidentally hit the production DB via SSH tunnel):

```powershell
docker run -d --name postgres-test -p 127.0.0.1:5433:5432 \
  -e POSTGRES_USER=dictara \
  -e POSTGRES_PASSWORD=hesoyam \
  -e POSTGRES_DB=dictara \
  postgres:16
```

2. Add to root `.env`:

```
TEST_DB_URL=jdbc:postgresql://localhost:5433/dictara
TEST_DB_PASSWORD=hesoyam
```

`build.gradle.kts` reads these vars from `.env` automatically and passes them to the test JVM — no IntelliJ run-config edits needed.

**Run tests:**

```bash
./gradlew test
# or just run them from IntelliJ as usual
```

Flyway runs all migrations on first connect. If the DB gets into a broken state, wipe and restart:

```powershell
docker exec postgres-test psql -U dictara -d dictara -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO dictara;"
```
