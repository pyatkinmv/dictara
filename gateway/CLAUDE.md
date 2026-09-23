# gateway — Kotlin Spring Boot API

Central HTTP entry point: auth, job persistence, orchestration.

## Architecture

```
Client (tg-bot / Flutter)  ──HTTP──>  Controllers (TranscribeController, TranscriptionUploadController,
                                       Tag/Speaker/SuggestionController, AuthController, ExportController)
                                       - audio goes to AudioStorage (GCS, the transcriber fetches it by gs:// reference)
                                             ↓
                                       OrchestratorService
                                       - single dispatchExecutor thread
                                       - dispatches one job at a time
                                             ↓
                              TranscriberClient  ──HTTP──>  transcriber
                              (submit by gs:// reference + poll)
                                             ↓
                                       SummarizerPort.analyze  (Gemini, Noop, or the local stub)
                                       one call: summary + title + tags + speaker names
                                             ↓
                                       SuggestionService.record (checks the proposals, keeps them as rows)
```

Two generations of API live side by side and must both keep working while the old clients exist:
- **Old** (Telegram bot `tg-bot`, `POST /transcribe`, `GET /jobs/{id}`, `/jobs/{id}/tags`…): kept as it was.
- **New** (Flutter client): `POST /transcriptions`, `GET /transcriptions` (paged, filtered), `GET /transcriptions/{id}`, the tag and speaker libraries `/tags`, `/speakers`, speaker assignment `PUT /transcriptions/{id}/speakers`, AI suggestions `POST /transcriptions/{id}/suggestions/...`. These answer 401 without a token. Migrations and API changes are **additive only** (see [docs/local-backend-integration.md](../docs/local-backend-integration.md), 5.2). Everything about how the pieces were built and why is in that document.

### Audio storage

Cloud Run enforces a hard 32 MiB HTTP request body limit, so audio is not streamed to the transcriber. `dictara.storage.gcs.bucket` (`GCS_UPLOADS_BUCKET`) is **required**: `GcsAudioStorage` uploads the file to that bucket and the gateway submits the job by `gs://` reference (`TranscriberClient.submitByReference`, `audio_meta.storage_uri`); the transcriber downloads it directly. Uploaded objects are not deleted by the application; a 90-day bucket lifecycle rule expires them (see [docs/deployment.md](../docs/deployment.md)). Under the `local` profile `LocalDbAudioStorage` keeps the file in the database instead.

Duplicates: the same user + SHA-256 of the file + model + language + diarize + number of speakers + summary mode (failed jobs excluded) is not transcribed again; the existing job is returned. The file is uploaded to storage before that check.

### Transcripts, speakers, suggestions (data model)

- `transcript_segments`: one row per phrase (`idx`, `start_s`, `end_s`, `text`, `voice`, `speaker_id`). The old JSON column `transcripts.segments` is still written too (dual write, marked `TODO(drop-json-segments)`) and is to be dropped in a later release.
- `transcript_voices`: a diarizer voice (`SPEAKER_01`) is a library speaker *in this transcript*. A phrase's speaker is its own mark, else its voice's, else nobody (shown as "Speaker N").
- Tags and speakers belong to a user; names are unique per user (speakers ignoring case). Their colors are computed from the name (`NameColors`), not stored.
- `transcript_suggestions`: what the AI proposes (title, tags of the library, a speaker for a voice), `status` pending/accepted/rejected. **Nothing is applied until the user accepts**; a speaker is created in the library only on accept. The model's answer is checked before it is kept (tag in the library, voice exists, the quoted evidence is in the transcript, a new name is a whole word of the transcript). `Summary: Off` skips the whole AI call.

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
| `done` | Complete (transcript + optional summary and suggestions available) |
| `failed` | Terminal failure (non-retryable transcriber error or 3 attempts exhausted) |

DB invariant: at most one submission can be `processing` at a time (enforced by partial unique index `idx_one_processing_submission`).

## Queue position

`GET /jobs/{id}` returns `queue_position` for `pending` submissions only:

```
queue_position = COUNT(pending submissions with createdAt < this.createdAt) + 1
```

Returns `null` for `processing`, `summarizing`, `done`, `failed`.

## Scheduled maintenance jobs

Background jobs that run automatically:

| Job | Schedule | What it does |
|-----|----------|--------------|
| `dedup_storage_uris` | Daily at 03:00 | Finds `audio_meta` rows with the same `content_hash` and updates them all to share the oldest `storage_uri`, making duplicate GCS objects unreferenced |
| `cleanup_orphaned_gcs_objects` | Weekly Sunday at 04:00 | Lists all objects in the GCS bucket, deletes any not referenced by `audio_meta.storage_uri`. Skips objects younger than 1 hour (grace period to avoid race with in-flight uploads) |
| `daily_db_backup` | Daily at 16:45 UTC | Queries all tables from `information_schema` (excluding `flyway_schema_history`), serializes each to gzipped JSON, uploads to `gs://{bucket}/backups/{date}/{table}.json.gz`. New tables added via Flyway are picked up automatically. **If the database grows large, this approach loads entire tables into memory and must be replaced with a streaming/pg_dump-based solution** (`transcript_segments` is now one of the big tables). |

Every run is recorded in the `job_runs` table (`status`: `running` → `completed`/`failed`, `rows_affected`, `finished_at`). Use `JobTracker.tracked(name) { ... }` to wrap any future job in the same way — one line, no boilerplate.

## Key files

| File | Purpose |
|------|---------|
| `service/OrchestratorService.kt` | Job lifecycle: dispatch, transcription polling, summarization (one call for summary and proposals) |
| `service/SubmissionStateService.kt` | DB state transitions (all `@Transactional`) |
| `service/SubmissionService.kt` | Reading and listing transcriptions, detail page data |
| `service/SuggestionService.kt` | Checks and stores AI proposals; accept / reject / accept all / dismiss all |
| `service/TagService.kt`, `SpeakerService.kt`, `TranscriptSpeakerService.kt` | Libraries and speaker assignment |
| `controller/TranscribeController.kt` | Old `POST /transcribe`, `GET /jobs/{id}`; shared `validateUpload` |
| `controller/TranscriptionUploadController.kt` | New `POST /transcriptions` |
| `controller/ExportController.kt` | GET /export — streams ZIP of all user transcriptions (transcript + summary + optional audio) |
| `client/TranscriberClient.kt` | HTTP client for the transcriber service |
| `port/SummarizerPort.kt`, `adapter/GeminiSummarizer.kt` | The Gemini prompt and structured-output schema (one JSON: summary, title, tags, speakers). `parse()` treats a summary with no ending punctuation as a failure (Gemini occasionally leaves an internal `"` unescaped, which truncates the JSON string mid-sentence but still leaves the rest of the object well-formed) — the prompt also tells the model not to use double quotes in the summary. Either failure reuses `OrchestratorService`'s existing 3-attempt retry, no separate retry logic needed. |
| `service/AuthService.kt`, `controller/AuthController.kt` | Telegram login: username → bot confirmation → JWT (`port/TelegramBotSimulator.kt` stands in for the bot under `local`) |
| `plan/PlanService.kt`, `PlanStrategy.kt` | Enforces and reports plan usage (`GET /users/me`, `controller/UserController.kt`) — a strategy per plan (`MonthlySubmissionLimitStrategy`, `NoLimitStrategy`), each names its own unit |
| `storage/GcsAudioStorage.kt` | Uploads audio to GCS (active outside the `local` profile) |
| `job/JobTracker.kt` | Wraps any block in a `job_runs` DB record (running → completed/failed) |
| `job/StorageMaintenanceService.kt` | The `@Scheduled` GCS maintenance jobs |
| `db/migration/` | Flyway migrations (additive only) |
| `db/local/` | Seed for the `local` profile (not on Flyway's default path) |

## Build

```bash
docker compose build gateway && docker compose up -d gateway
```

## Local development (profile `local`)

`scripts\dev.ps1` starts Postgres (`docker-compose.dev.yml`, database `dictara_dev`) and the gateway with the `local` profile; `scripts\dev-reset.ps1` recreates the database. Under this profile a stub transcriber (`adapter/StubTranscriber.kt`) replaces `TranscriberClient`, a stub summarizer (`adapter/StubSummarizer.kt`, proposals worked out by simple rules) replaces Gemini, a stub bot (`adapter/LocalTelegramBotSimulator.kt`) auto-confirms Telegram logins after a short pause (username `unknown` is left unregistered on purpose, to try the "not found" screen) so the real login screen works without `tg-bot` or a Telegram account, audio is kept in the database (`storage/LocalDbAudioStorage.kt`, table from `db/local/`), requests without a token still belong to a seeded dev user (`filter/LocalDevAuthFilter.kt`, kept alongside the real login for quick `curl`/script testing), and the scheduled backup/cleanup jobs do not run.

`config/LocalProfileGuard.kt` makes the profile refuse to start unless the database is `dictara_dev` on localhost and `GCS_UPLOADS_BUCKET` is unset — keep it that way, the cleanup job would otherwise delete real audio. Everything local is off by default (`@Profile("local")` / `!local`, `db/local` is not on Flyway's default path). Details: [docs/local-backend-integration.md](../docs/local-backend-integration.md).

**To see the seeded demo data (transcriptions, tags, speakers) through the real login screen**, log in with the Telegram username **`dev`** — `db/local/R__seed.sql` seeds an `auth_identities` row (`telegram` / `local-dev`) pointing at the same dev user `LocalDevAuthFilter` uses, so that username lands on the account that owns the seed data instead of a brand-new empty one (any other username creates a fresh account, same as it would in production).

## Running tests

```bash
./gradlew test
```

Integration tests use Testcontainers (a throwaway `postgres:16`, so Docker must be running); nothing needs to be configured, and no `TEST_DB_*` variable should be set. They `TRUNCATE audio_meta CASCADE` between tests, so **never point them at a database that holds data** (that is what `TEST_DB_URL` in the root `.env` would do; `build.gradle.kts` still forwards such variables if present). The `local`-profile test starts its own container named `dictara_dev`.
