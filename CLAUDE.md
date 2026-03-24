# Dictara — Local Audio Transcription Service

## What it does
Transcribes audio/video files using Whisper with optional speaker diarization. Runs fully locally in Docker. The gateway is the single HTTP entry point; the transcriber does the actual Whisper work; tg-bot handles Telegram; the Flutter web client provides a browser UI.

## Modules

| Module | Language | Purpose | Docs |
|--------|----------|---------|------|
| `gateway/` | Kotlin / Spring Boot | HTTP API, auth, job persistence, orchestration | — |
| `transcriber/` | Python | FastAPI transcription service — Whisper + pyannote | [transcriber/CLAUDE.md](transcriber/CLAUDE.md) |
| `tg-bot/` | Kotlin | Telegram bot — sends audio, receives transcript.txt | [tg-bot/CLAUDE.md](tg-bot/CLAUDE.md) |
| `app/` | Dart/Flutter | Web client — Material 3 UI | [app/CLAUDE.md](app/CLAUDE.md) |

```
dictara/
  gateway/            Kotlin Spring Boot service (auth, persistence, orchestration)
  transcriber/        Python service (Whisper + FastAPI)
  tg-bot/             Kotlin Telegram bot
  app/                Flutter web client
  docker-compose.yml  Orchestrates all services
  .env                Secrets (see table below)
```

## Configuration (env vars)

| Var | Service | Default | Description |
|-----|---------|---------|-------------|
| `TELEGRAM_TOKEN` | tg-bot | — | Bot token from @BotFather |
| `POSTGRES_PASSWORD` | gateway, postgres | — | PostgreSQL password |
| `JWT_SECRET` | gateway | — | Secret for signing JWT tokens |
| `TELEGRAM_BOT_USERNAME` | gateway | — | Bot @username without `@` (used in login hint URL) |
| `TELEGRAM_API_ID` | telegram-bot-api | — | Telegram API ID (large file support) |
| `TELEGRAM_API_HASH` | telegram-bot-api | — | Telegram API hash (large file support) |
| `HF_TOKEN` | transcriber | — | HuggingFace token — required for diarization |
| `HF_HOME` | transcriber | `/models` | HF model cache path (mapped to `model-cache` volume) |
| `WHISPER_MODELS` | transcriber | `small,turbo` | Comma-separated list of Whisper models to load |
| `GATEWAY_URL` | tg-bot | `http://gateway:8080` | Gateway URL (auto-set in Docker) |
| `TRANSCRIBER_URL` | gateway | `http://transcriber:8000` | Transcriber URL (auto-set in Docker) |
| `GEMINI_API_KEY` | gateway | — | Google Gemini key — enables summarization |
| `GEMINI_MODEL` | gateway | `gemini-2.5-flash` | Gemini model to use |
| `SUMMARIZER_PROVIDER` | gateway | `gemini` | Summarization backend |

## Build & run

```bash
# First time — builds all images and downloads models (~4–5 GB, takes ~15 min)
docker compose build
docker compose up -d

# Rebuild a single module after code changes
docker compose build gateway    && docker compose up -d gateway
docker compose build transcriber && docker compose up -d transcriber
docker compose build tg-bot      && docker compose up -d tg-bot
docker compose build app-material && docker compose up -d app-material

# Check health
curl http://localhost:8080/health
```

## docker-compose services

| Service | Build context | Exposes | Notes |
|---------|--------------|---------|-------|
| `gateway` | `./gateway` | `:8080` (localhost only) | Spring Boot; depends on postgres + transcriber |
| `transcriber` | `./transcriber` | `:8000` | Python/FastAPI; health-checked |
| `postgres` | image `postgres:16` | `:5432` | Persistent volume `postgres-data` |
| `telegram-bot-api` | image `aiogram/telegram-bot-api` | — | Local Bot API for >20 MB files |
| `tg-bot` | `./tg-bot` | — | Outbound only; depends on gateway |
| `app-material` | `./app` | `:3000` | Flutter web — Material 3 |

Model downloads cache to the `model-cache` Docker volume. `docker compose down` keeps volumes; only `docker compose down -v` deletes them.

## Resource limits

`scripts/set-limits.sh` calculates transcriber memory/CPU limits as a percentage of the host machine's resources and writes them to `.env.limits`. Run it before `docker compose up` to apply dynamic limits:

```bash
bash scripts/set-limits.sh
set -a; source .env.limits; set +a
docker compose up -d transcriber
```

CI runs this automatically on every deploy. Without it, `docker-compose.yml` falls back to hardcoded defaults.
