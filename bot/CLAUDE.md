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
| `DictaraBot.kt` | `TelegramLongPollingBot` — audio handler, `/settings` command |
| `DictaraClient.kt` | HTTP client: submit job, poll until done, format segments |
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
Defaults: model=`accurate`, diarize=`true`.
Resets on bot restart — acceptable since defaults are the preferred values.

## Bot UX

**Normal flow:**
```
User sends audio file
Bot: "Transcribing with accurate + diarization..."
Bot: [transcript.txt]  "Done in 16m 42s."
```

**`/settings` command:**
```
Bot: Settings
     [Fast]       [Accurate [x]]
     [Diarize on [x]]  [Diarize off]
(clicking a button edits the message in place with updated checkmarks)
```

Callback data format: `set_model:fast`, `set_model:accurate`, `set_diarize:on`, `set_diarize:off`

## Telegram setup

1. Create bot via [@BotFather](https://t.me/BotFather)
2. Copy the token
3. Add `TELEGRAM_TOKEN=...` to root `.env`

## Dependencies

- `org.telegram:telegrambots:6.9.7` — stable long-polling bot API
- `com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1` — JSON parsing for API responses

## Build

```bash
# From repo root:
docker compose build bot
docker compose up -d bot
```
