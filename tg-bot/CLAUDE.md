# tg-bot — Kotlin Telegram Bot

Telegram bot that forwards audio files to the gateway and returns transcripts. Also handles the Telegram-side of the web login flow.

## Architecture

```
User sends audio
       ↓
  DictaraBot.kt (long polling via local telegram-bot-api)
       ↓
  DictaraClient.kt  ──HTTP──>  gateway :8080
  - POST /transcribe             - persists job, queues to transcriber
  - polls /jobs/{id} every 5s    - returns progress + result
       ↓
  sends transcript.txt to user

Web login flow:
  DictaraBot.kt polls /auth/pending-login-notifications every 2s
       ↓
  sends confirm/reject keyboard to user
       ↓
  DictaraClient.kt  ──HTTP──>  gateway :8080
  - POST /auth/login-link/confirm-callback
  - POST /auth/login-link/reject
```

## Key files

| File | Purpose |
|------|---------|
| `Main.kt` | Entry point — reads env vars, registers bot |
| `DictaraBot.kt` | `TelegramLongPollingBot` — audio handler, login flow, `/settings` command |
| `DictaraClient.kt` | HTTP client: transcription, login notifications, auth callbacks |
| `GeminiClient.kt` | Gemini API client — adaptive summarization by audio duration |
| `UserSettings.kt` | Per-user prefs stored in `ConcurrentHashMap` (in-memory) |
| `build.gradle.kts` | Kotlin + telegrambots + shadow JAR plugin |
| `Dockerfile` | Multi-stage: Gradle build → eclipse-temurin JRE slim |

## Many files at once

A user can forward a whole chat of recordings in one go. Every recording runs on its own thread (`DictaraBot`'s cached pool), and its life is: copy the file from the local telegram-bot-api (`downloadAudioFile`) → send it to the gateway (`DictaraClient.submit`) → wait for the transcript (`DictaraClient.poll`, every 5 s, up to hours) → answer. Only the first two steps are heavy (disk, memory, bandwidth, and in the gateway a buffer per upload), so `UploadPipeline` runs them through an `UploadGate` of 3 places (`DictaraBot.MAX_PARALLEL_UPLOADS`, a fair semaphore): the others wait for their turn, in the order they came, then copy and send. The copy is deleted as soon as the gateway has taken the file, not after the transcript. Waiting for the transcript is not limited (the gateway transcribes one file at a time anyway and shows "Waiting in queue... Position: N"). The "⏳ Transcribing…" message is sent at once for every file, before the wait, and a file that waits for a place keeps that text.

Without the gate the gateway ran out of memory (`OutOfMemoryError`, 10 in a few minutes for ~50 files) and the local telegram-bot-api container crashed and restarted. Not a bug of the limit, but to keep in mind: Telegram limits what a bot may send (about one message a second to one chat, about 30 a second in all; more gets 429 and, kept up, a pause or a block). Every forwarded file is a "Transcribing…" message, edits of it (at most one every 30 s, `DictaraClient.poll`) and the answer, so a very large forward, or many users, can reach it. Not handled yet.

Tests: `UploadGateTest`, `UploadPipelineTest`, `DictaraClientTest` (a stand-in gateway, `MockWebServer`) and `ForwardedChatTest` (80 recordings at once through the real gate, pipeline and client).

## Model aliases

| Bot alias | API model |
|-----------|-----------|
| `fast` | `small` |
| `accurate` | `turbo` |

`large-v3` is also available on the transcriber API directly but is not exposed as a bot alias (too slow on CPU; only viable on GPU).

## User settings

Stored in-memory keyed by **chat ID**. Defaults: model=`accurate`, diarize=`true`, summaryMode=`AUTO`, language=`auto`, numSpeakers=`null`. Resets on bot restart.

## Environment variables

| Var | Default | Description |
|-----|---------|-------------|
| `TELEGRAM_TOKEN` | — | Bot token from @BotFather |
| `GATEWAY_URL` | `http://gateway:8080` | Gateway service URL |
| `DICTARY_BASE_URL` | `https://dictary.app` | Public base URL — used in web login links sent to users |
| `TELEGRAM_API_URL` | `https://api.telegram.org` | Local Bot API URL for large files |
| `GEMINI_API_KEY` | — | Google Gemini API key (summarization disabled if unset) |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Gemini model |

## Bot UX

**Normal transcription flow:**
```
User sends audio file
Bot: "⏳ Transcribing your audio...
     Model: Accurate | Speakers: on (2) | Lang: RU | Summary: on"
  → pending: shows queue position while waiting
  → processing: edited live with progress percentages (transcribing/diarizing)
  → summarizing: shown while gateway summarizes (next job already dispatched)
Bot: [transcript.txt]  caption: "Done in 4m 12s.\n\n<summary>"
```

**`/settings` command:**
```
Bot: Settings
     [Fast]            [Accurate ✓]
     [Diarize on ✓]    [Diarize off]
     [📝 Summary: Auto ✓]
     [🌐 Language: Auto]
     [👥 Speakers: Auto]
```
Language and Speakers open submenus. "Other..." for language prompts a text reply.

**Web login flow:**
- Bot polls `/auth/pending-login-notifications` every 2s
- Sends "Confirm login to Dictara?" with [✓ Confirm] / [✗ Reject] buttons
- On `/start`: checks `/auth/pending-login-for-username` and shows confirm prompt if pending
- On every private message: fires `POST /auth/bot-started` (once per session) so the gateway knows the user can receive messages

## Callback data format

- `set_model:fast`, `set_model:accurate`
- `set_diarize:on`, `set_diarize:off`
- `open_summary_mode`, `set_summary_mode:<OFF|AUTO|BRIEF|CONCISE|FULL>`
- `open_language`, `set_language:<code>`, `lang_custom`
- `open_speakers`, `set_speakers:<n|auto>`
- `back_settings`
- `confirm_login:<token>`
- `reject_login:<token>`

## Summarization (GeminiClient)

Invoked after transcription if `summaryMode != OFF` and `GEMINI_API_KEY` is set. Mode `AUTO` picks format by audio duration:

| Audio length | Format |
|-------------|--------|
| < 2 min | 1–2 sentences |
| 2–15 min | Concise paragraph (3–5 sentences) |
| > 15 min | Structured: Summary, Key points, Conclusions, Action items |

## Build

```bash
docker compose build tg-bot
docker compose up -d tg-bot
```
