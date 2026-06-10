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

## Key files

| File | Purpose |
|------|---------|
| `OrchestratorService.kt` | Job lifecycle: dispatch, transcription polling, summarization |
| `SubmissionStateService.kt` | DB state transitions (all `@Transactional`) |
| `TranscribeController.kt` | POST /transcribe, GET /jobs/{id} |
| `ExportController.kt` | GET /export — streams ZIP of all user transcriptions (transcript + summary + optional audio) |
| `TranscriberClient.kt` | HTTP client for transcriber service |
| `AudioStorageClient.kt` | Uploads audio to GCS for the reference-based submit path (active only when `dictara.storage.gcs.bucket` is set) |
| `db/migration/` | Flyway migrations |

## Build

```bash
docker compose build gateway && docker compose up -d gateway
```
