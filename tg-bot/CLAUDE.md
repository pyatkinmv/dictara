# bot — Kotlin Telegram Bot

Telegram bot that forwards audio files to the core service and returns transcripts. Written in Kotlin, runs as a separate Docker service.

## Architecture

```
User sends audio
       ↓
  DictaraBot.kt (long polling)
       ↓
  DictaraClient.kt  ──HTTP──>  transcriber service :8000
  - POST /transcribe             - processes job
  - polls /jobs/{id} every 5s
       ↓
  sends transcript.txt to user
```

## Key files

| File | Purpose |
|------|---------|
| `Main.kt` | Entry point — reads env vars, registers bot |
| `DictaraBot.kt` | `TelegramLongPollingBot` — audio handler, `/settings` command, summary overflow logic |
| `DictaraClient.kt` | HTTP client: submit job, poll with live progress callbacks, format segments |
| `GeminiClient.kt` | Gemini API client — adaptive summarization by audio duration |
| `UserSettings.kt` | Per-user prefs stored in `ConcurrentHashMap` (in-memory) |
| `build.gradle.kts` | Kotlin + telegrambots 6.9.7 + shadow JAR plugin |
| `Dockerfile` | Multi-stage: Gradle build → eclipse-temurin JRE slim |

## Model aliases

The bot exposes friendly names; the core API uses raw model names:

| Bot alias | Core model |
|-----------|-----------|
| `fast` | `small` |
| `accurate` | `large-v3` |

Defined in `DictaraClient.kt` as `MODEL_ALIASES`. To add a future model, add one entry here.

## User settings

Stored in-memory per Telegram user ID (`ConcurrentHashMap<Long, UserPrefs>`).
Defaults: model=`accurate`, diarize=`true`, summarize=`false`, language=`auto`, numSpeakers=`null` (auto).
Resets on bot restart — acceptable since defaults are the preferred values.

## Environment variables

| Var | Default | Description |
|-----|---------|-------------|
| `TELEGRAM_TOKEN` | — | Bot token from @BotFather |
| `DICTARA_URL` | `http://dictara:8000` | Core service URL |
| `GEMINI_API_KEY` | — | Google Gemini API key. Summarization is disabled if unset |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Gemini model to use for summarization |

## Bot UX

**Normal flow:**
```
User sends audio file
Bot: "⏳ Transcribing your audio...

Model: Accurate | Speakers: on (2) | Lang: RU | Summary: on"
  (edited live with progress: "🎙 Transcribing audio... ▓▓▓▓▓░░░░░ 52% (2m 4s / 3m 58s)")
  (then: "👥 Detecting speakers... ▓▓▓▓░░░░░░ 40%")
Bot: [transcript.txt]  "Done in 4m 12s."   ← status message deleted, doc sent
Bot (caption edited): "Done in 4m 12s.

<summary from Gemini>"
```
If summary exceeds the 1024-char Telegram caption limit, caption stays as `"Done in Xm Ys."` and summary is sent as a separate text message.

**`/settings` command:**
```
Bot: Settings
     [Fast]            [Accurate ✓]
     [Diarize on ✓]    [Diarize off]
     [Summarize on]    [Summarize off ✓]
     [🌐 Language: Auto]
     [👥 Speakers: Auto]
(clicking a button edits the message in place with updated checkmarks)
```

Language and Speakers open submenus. Language submenu: Auto / EN / RU / DE / ES / FR / Other... / ← Back. Tapping "Other..." replaces the message with a text prompt; the user's next message is captured as the language code and settings are restored. Speakers submenu: Auto / 1 / 2 / 3 / 4 / 5+ / ← Back. "5+" maps to auto-detection.

Callback data format:
- `set_model:fast`, `set_model:accurate`
- `set_diarize:on`, `set_diarize:off`
- `set_summarize:on`, `set_summarize:off`
- `open_language`, `set_language:<code>`, `lang_custom`
- `open_speakers`, `set_speakers:<n|auto>`
- `back_settings`

## Telegram setup

1. Create bot via [@BotFather](https://t.me/BotFather)
2. Copy the token
3. Add `TELEGRAM_TOKEN=...` to root `.env`

## Summarization (GeminiClient)

Invoked after transcription if `summarize=true` and `GEMINI_API_KEY` is set. Prompt scales by audio duration:

| Audio length | Summary format |
|-------------|---------------|
| < 2 min | 1–2 sentences, no headers |
| 2–15 min | Concise paragraph (3–5 sentences) |
| > 15 min | Structured: 📝 Summary, Key points, Conclusions, ✅ Action items (only if present) |

All text (including headers) is in the transcript's language.

## Dependencies

- `org.telegram:telegrambots:6.9.7` — stable long-polling bot API
- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP client for core service and Gemini API
- `com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1` — JSON parsing

## Build

```bash
# From repo root:
docker compose build bot
docker compose up -d bot
```
