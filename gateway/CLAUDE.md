# gateway — Kotlin Spring Boot API

Central HTTP entry point: auth, job persistence, orchestration.

## Architecture

```
Client (tg-bot / Flutter)  ──HTTP──>  TranscribeController
                                             ↓
                                       OrchestratorService
                                       - single dispatchExecutor thread
                                       - dispatches one job at a time
                                             ↓
                              TranscriberClient  ──HTTP──>  transcriber :8000
                              (submit + poll)
                                             ↓
                                       SummarizerPort (Gemini or Noop)
```

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
| `TranscriberClient.kt` | HTTP client for transcriber service |
| `db/migration/` | Flyway migrations (V1–V8) |

## Build

```bash
docker compose build gateway && docker compose up -d gateway
```
