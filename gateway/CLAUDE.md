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

Cloud Run enforces a hard 32 MiB HTTP request body limit, so audio is not streamed to the transcriber. `dictara.storage.gcs.bucket` (`GCS_UPLOADS_BUCKET`) is **required**: `GcsAudioStorage` uploads the file to that bucket and the gateway submits the job by `gs://` reference (`TranscriberClient.submitByReference`, `audio_meta.storage_uri`); the transcriber downloads it directly. Uploaded objects are not deleted by the application and the bucket has **no lifecycle rule** (it used to delete audio after 90 days, which left ~80% of the old history with a transcript but no file; removed in October 2026, see [docs/deployment.md](../docs/deployment.md)). A record whose file is gone answers `410` on `POST /transcriptions/{id}/audio-token` and the player says "Audio is no longer available". Under the `local` profile `LocalDbAudioStorage` keeps the file in the database instead.

`audio_meta.duration_s` is the length of the audio as the transcriber measured it. V24 dropped the old `transcripts.audio_duration_s` without copying it, so everything made before July 2026 had no length (the page showed "0 min"); V35 filled those from the end of the last phrase (a few seconds short of the real length on average). A transcription with no phrases stays null and the screens show nothing for it.

`audio_meta.metadata` (JSONB) holds whatever the transcriber's `ffprobe` found on the original file — codec, bit rate, sample rate, channels and its raw container tags (`tags.creation_time` is the actual recording date on most phone-recorded M4A/MP4, distinct from `audio_meta.created_at`, which is upload time). The gateway never inspects the file itself for this — it only stores what `TranscriberJobSnapshot.audioMetadata` reports (`result.audio_metadata` in the transcriber's job response), since the gateway's own upload path deliberately never buffers the file (`GcsAudioStorage.upload` streams straight through) and has no ffmpeg/ffprobe in its image; the transcriber is the only place in the pipeline that ever has the original bytes on local disk. Null when `ffprobe` found nothing or the transcript has no phrases yet. Not surfaced anywhere yet — stored for future use.

Audio is an object of its own, independent of user and options; a transcription is a result that depends on the options. Three layers on every upload (`AudioIngestService`, `AudioRestoreService`, `SubmissionService.createSubmission`):

1. **Stored once per content.** The SHA-256 is taken first; if any record (any user, any options) has that content and its object still exists (`AudioStorage.isGone`), the new record points at it and nothing is uploaded.
2. **Restore first.** Records with this content whose object is gone are repointed to a live copy, else to the file just uploaded (any user, any options). If the uploader has such a record the upload only restores the audio and answers with that existing submission (`dedup=true`): no new transcription, whatever options came with it. `isGone` is true only on a confirmed 404; any doubt means "exists".
3. **Same result is not made twice.** Same user + SHA-256 + model + language + diarize + number of speakers + summary mode (failed jobs excluded) returns the existing job. This is about results, so the options matter.

### Transcripts, speakers, suggestions (data model)

- `transcript_segments`: one row per phrase (`idx`, `start_s`, `end_s`, `text`, `voice`, `speaker_id`). The old JSON column `transcripts.segments` is still written too (dual write, marked `TODO(drop-json-segments)`) and is to be dropped in a later release.
- `transcript_voices`: a diarizer voice (`SPEAKER_01`) is a library speaker *in this transcript*. A phrase's speaker is its own mark, else its voice's, else nobody (shown as "Speaker N"). `GET /transcriptions/{id}` leaves this resolution to the client (it returns both the raw `segments[].speaker` and `voices`, and Flutter combines them — `TranscriptionDetail.speakerOfSegment`/`presentSpeakers`). Text meant to be read outside the app — `.txt` download, Copy, the ZIP export (`SubmissionService.resolvedTranscriptText`) — resolves it server-side instead, since there is no client there to do it; `GET /jobs/{id}`'s `formatted_text` (the old Telegram-bot reader, via `TranscriptFormatter.format`) is the one exception left with raw labels, kept as it was for that client.
- Tags and speakers belong to a user; names are unique per user (speakers ignoring case). Their colors are computed from the name (`NameColors`), not stored.
- `transcript_suggestions`: what the AI proposes (title, tags of the library, a speaker for a voice), `status` pending/accepted/rejected. **Nothing is applied until the user accepts**; a speaker is created in the library only on accept. The model's answer is checked before it is kept (tag in the library, voice exists, the quoted evidence is in the transcript, a new name is a whole word of the transcript). `Summary: Off` skips the whole AI call.

### Regenerate summary

`POST /transcriptions/{id}/regenerate-summary` (`SummaryRegenerationService`) asks Gemini again about a finished transcription; the transcript is not touched. **How many times per transcription is the user's plan**: `regenerations_per_transcription` in `plans.constraints` (`PlanService.regenerationLimit`; a number, 0 for none, no key = no limit like the other constraints). V39 set it to 1 for `free` and `anonymous`; `unlimited` (Ultra) has no key. 202 when it started, 404 for someone else's transcription, 409 when it is not ready, a run is going, the plan's limit for this transcription is used up (the message says how many the plan allows), the plan has none, there is nothing to summarize or no summary service. `GET /transcriptions/{id}` carries `summary_regeneration: {state, used, max?, at?, error?}`; `state` is `available`, `running`, `used` (the limit is reached), or `unavailable`; `used` counts the times it was done, `max` is the plan's limit (left out when there is none), `at` is when it was last done. It is `submissions.summary_regenerations` (the count) with `summary_regeneration` (NULL / `running`) and `summary_regenerated_at`, taken by one conditional UPDATE (`SummaryRegenerationQueries.claim`, which also checks the limit), so two clicks cannot both start a run nor go past the limit; the count goes up only when a run succeeds (a failed run, after three attempts as on the first analysis, and a restart (`@PostConstruct`) give the run back without counting it). `error` is why the user's last attempt failed, in plain words (`SummaryRegenerationService.describeFailure`: out of credits, busy, not available, no usable answer; never the raw reply of Gemini), kept in `submissions.summary_regeneration_error` (V41) until the next attempt starts or succeeds, so it is still there when the page is opened later. Changing a user's plan changes what is allowed at once. The submission's `status` stays `done` throughout.

What a run does: asks with the transcript (with what the user has settled about speakers: a voice they named, or a phrase they marked, is sent under that name instead of `SPEAKER_xx`, so the summary can name people; voices nobody named keep their label so that a proposal can say which voice it is about, and the prompt then asks for proposals only for those), the user's tag and speaker libraries (with their descriptions) and the language (`summaryLanguage`: the user's choice, else `detected_language`, else Cyrillic-dominant text means Russian, else the prompt says to answer in the text's language); a summary mode of `off` is asked as `auto`. Then, in one transaction, replaces the `summaries` row's text and refreshes the proposals (`SuggestionService.refresh`): unsettled (`pending`) proposals and the title row are deleted and the new ones recorded; **accepted and rejected tags and speakers stay** (so a rejected one is not proposed again, and a voice already named is not asked about), a rejected title is not proposed again, and an accepted title stays the transcription's title until the user accepts a different one. Tested over histories of accepted, rejected and waiting proposals in `SummaryRegenerationIntegrationTest`.

### Backlog run

`POST /admin/reanalyze` (`AdminController`, `BacklogReanalysisService`; body `{user_id, ids, dry_run?, force?}`, header `X-Admin-Key`) asks Gemini again, in the background, about the given transcriptions of one user: the same step as the user's own regeneration (`SummaryRegenerationService.analyzeAndReplace`: summary replaced, unsettled proposals refreshed, settled ones kept), but **not counted** against the plan and not touching the user's button (it takes the "running" state like a regeneration does, so the two never run at once; one the user is regenerating at that moment is left for later). Built so as not to spend money by accident: the caller names the ids (at most `dictara.admin.max-ids-per-call`, 300); `dry_run` says which are eligible, why the others are left out (`invalid`, `not_found`, `not_owner`, `not_ready`, `already_done`, `duplicate`) and a rough cost; one batch at a time (409), one transcription after another with a pause (`reanalyze-pause-ms`), a transcription done by this run is marked (`submissions.analysis_backfilled_at`, V40) and skipped unless `force`, a batch stops by itself after `stop-after-failures` failures in a row and can be stopped (`POST /admin/reanalyze/stop`). `GET /admin/reanalyze/status` reports done / failed, tokens (`GeminiSummarizer` reads `usageMetadata`) and the cost so far. Off (404) while `ADMIN_KEY` is empty; nginx answers 404 for `/api/admin/`, so it is only reachable on the VM at `localhost:8080`. Driven by `scripts/reanalyze.ps1`, see [docs/ops/runbook.md](../docs/ops/runbook.md).

### What `gemini-2.5-flash` does not do reliably (found in the pilot of the backlog run)

Tried on 10 real recordings, then directly against the API on six of them, two runs each, in two other answer formats. Kept as it is, on purpose: no code works around these, the answer is a stronger model or better descriptions.

- **A rule in a tag description is not kept**, even in capitals ("NEVER together with localisation_daily"): a daily standup in which people report on a project still gets that project's tag. Tag descriptions are guidance, not constraints.
- **Self-reported confidence does not separate right from wrong**: asked for 0-100, it gave 80-95 to irrelevant tags and 90-95 to a wrong speaker, and the two runs of one recording differed a lot. So there is no confidence field and no threshold.
- **Forcing one choice works better** (one "primary" tag was right on all six recordings, both runs; the "also" tags were noisy) and **names exact as listed work better than numbers** (numbers of the library were not returned at all on a 14-voice daily). Neither is implemented.
- **A library speaker named "me"** is recognised in about 7 of 12 answers; otherwise the model proposes the name from the description (a duplicate if accepted). Name the speaker by the real name.
- The proposals are only proposals: the user settles each one.

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
| `dedup_storage_uris` | Daily at 03:00 | Points `audio_meta` rows with the same `content_hash` at the oldest object that still exists in the bucket (rows pointing at a gone object are healed this way; a hash with no live object is left alone), making duplicate GCS objects unreferenced |
| `cleanup_orphaned_gcs_objects` | Weekly Sunday at 04:00 | Lists all objects in the GCS bucket, deletes any not referenced by `audio_meta.storage_uri`. Skips objects younger than 1 hour (grace period to avoid race with in-flight uploads) |
| `daily_db_backup` | Daily at 16:45 UTC | Queries all tables from `information_schema` (excluding `flyway_schema_history`), serializes each to gzipped JSON, uploads to `gs://{bucket}/backups/{date}/{table}.json.gz`. New tables added via Flyway are picked up automatically. Each table is read through a cursor straight into the gzip stream, so only the compressed bytes are held in memory (the earlier version read whole tables into a list and ran out of heap on the 2 GB prod VM once `transcript_segments` held ~374k rows; measured peak now ~150 MB). It is a JSON dump, not `pg_dump`: restoring is manual. |

Every run is recorded in the `job_runs` table (`status`: `running` → `completed`/`failed`, `rows_affected`, `finished_at`). Use `JobTracker.tracked(name) { ... }` to wrap any future job in the same way — one line, no boilerplate.

## Key files

| File | Purpose |
|------|---------|
| `service/OrchestratorService.kt` | Job lifecycle: dispatch, transcription polling, summarization (one call for summary and proposals) |
| `service/SubmissionStateService.kt` | DB state transitions (all `@Transactional`) |
| `service/SubmissionService.kt` | Reading and listing transcriptions, detail page data |
| `service/SuggestionService.kt` | Checks and stores AI proposals; accept / reject / accept all / dismiss all |
| `service/TagService.kt`, `SpeakerService.kt`, `TranscriptSpeakerService.kt` | Libraries and speaker assignment |
| `service/AudioIngestService.kt`, `AudioRestoreService.kt` | Hash first / store once per content; repoint records whose file is gone when the same bytes come again |
| `controller/TranscribeController.kt` | Old `POST /transcribe`, `GET /jobs/{id}`; shared `validateUpload` |
| `controller/TranscriptionUploadController.kt` | New `POST /transcriptions` |
| `controller/ExportController.kt` | GET /export — streams ZIP of all user transcriptions (transcript + summary + optional audio) |
| `client/TranscriberClient.kt` | HTTP client for the transcriber service |
| `port/SummarizerPort.kt`, `adapter/GeminiSummarizer.kt` | The Gemini prompt and structured-output schema (one JSON: summary, title, tags, speakers). The language of summary and title is named in the prompt, before and after the transcript: the user's choice, else the language the transcriber detected (`summaryLanguage` in `OrchestratorService`), because "the language of the transcript" alone made Gemini answer in English for Russian recordings. A long summary asks for a bullet per topic with names, numbers and one action item per person (a plain "key points, be concise" gave 1,400–3,000 characters on the same recording and left out who does what; tried on three real recordings). The quote (`evidence`) the model gives with a speaker does not decide anything: it is kept when it is in the transcript (with the `[timestamp]` and `[SPEAKER_xx]` markers of a copied line removed) and logged when it is not. It used to have to match, and since models copy lines inexactly every proposal for a long meeting was dropped; a proposal is still dropped when its voice does not exist, its name is not valid, or a name that is not in the library is not a word of the transcript. Library tags and speakers are matched by meaning across languages and alphabets ("Nastya" = "Настя") and copied as listed; only a new speaker name is written as said in the transcript. Gemini sometimes returns the structured summary with no line breaks at all (one line: `...rollouts.Key points:• A• B...`; seen in about 5 of 6 runs of the old prompt and 1 of 6 of the detailed one), so `parse()` puts them back with `util/SummaryFormat.repair` (bullets on their own lines, short headers ending in a colon on their own lines after a blank line; only when the text is clearly flattened, so a good answer is untouched) and the prompt asks for line breaks and a bold lead-in per bullet. `parse()` treats a summary with no ending punctuation as a failure (Gemini occasionally leaves an internal `"` unescaped, which truncates the JSON string mid-sentence but still leaves the rest of the object well-formed) — the prompt also tells the model not to use double quotes in the summary. Either failure reuses `OrchestratorService`'s existing 3-attempt retry, no separate retry logic needed. |
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
