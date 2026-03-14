# Dictara — Local Audio Transcription Service

## What it does
Transcribes audio/video files using Whisper with optional speaker diarization. Runs fully locally in Docker — no cloud APIs, no data leaving your machine (except HuggingFace model download on first start).

## Modules

| Module | Language | Purpose | Docs |
|--------|----------|---------|------|
| `transcriber/` | Python | FastAPI transcription service — Whisper + pyannote | [transcriber/CLAUDE.md](transcriber/CLAUDE.md) |
| `tg-bot/` | Kotlin | Telegram bot — sends audio, receives transcript.txt | [tg-bot/CLAUDE.md](tg-bot/CLAUDE.md) |
| `app/` | Dart/Flutter | Web client — file upload, progress, transcript download | |

```
dictara/
  transcriber/        Python service (Whisper + FastAPI)
  tg-bot/             Kotlin Telegram bot
  app/                Flutter web client
  docker-compose.yml  Orchestrates all services
  .env                Secrets (HF_TOKEN, TELEGRAM_TOKEN)
```

## Configuration (env vars)

| Var | Module | Default | Description |
|-----|--------|---------|-------------|
| `WHISPER_MODELS` | core | `small,large-v3` | Comma-separated list of Whisper models to load |
| `HF_TOKEN` | core | — | HuggingFace token. Required for diarization |
| `HF_HOME` | core | `/models` | HF model cache path (mapped to `model-cache` volume) |
| `TELEGRAM_TOKEN` | tg-bot | — | Telegram bot token from @BotFather |
| `DICTARA_URL` | tg-bot | `http://transcriber:8000` | Transcriber service URL (auto-set in Docker) |

## Per-request API params (`/transcribe`)

| Param | Default | Description |
|-------|---------|-------------|
| `model` | `small` | `small` or `large-v3` |
| `language` | auto | ISO language code e.g. `en`, `ru` |
| `diarize` | `false` | Add speaker labels |
| `num_speakers` | auto | Exact speaker count hint for diarization |

## Build & run

```bash
# First time — builds all images and downloads models (~4GB, takes ~15 min)
docker compose build
docker compose up -d

# Rebuild a single module after code changes
docker compose build transcriber && docker compose up -d transcriber
docker compose build tg-bot      && docker compose up -d tg-bot
docker compose build app         && docker compose up -d app

# Check health
curl http://localhost:8000/health
```

## docker-compose services

| Service | Build context | Exposes |
|---------|--------------|---------|
| `transcriber` | `./transcriber` | `:8000` (HTTP API) |
| `tg-bot` | `./tg-bot` | — (outbound only, long-polling) |
| `app` | `./app` | `:3000` (Flutter web UI) |

Model downloads cache to the `model-cache` Docker volume. `docker compose down` keeps the volume; only `docker compose down -v` deletes it.
