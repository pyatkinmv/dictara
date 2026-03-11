# Dictara — Local Audio Transcription Service

## What it does
Transcribes audio/video files using Whisper with optional speaker diarization. Runs fully locally in Docker — no cloud APIs, no data leaving your machine (except HuggingFace model download on first start).

## Modules

| Module | Language | Purpose | Docs |
|--------|----------|---------|------|
| `core/` | Python | FastAPI transcription service — Whisper + pyannote | [core/CLAUDE.md](core/CLAUDE.md) |
| `bot/` | Kotlin | Telegram bot — sends audio, receives transcript.txt | [bot/CLAUDE.md](bot/CLAUDE.md) |

```
dictara/
  core/               Python service (Whisper + FastAPI)
  bot/                Kotlin Telegram bot
  docker-compose.yml  Orchestrates both services
  .env                Secrets (HF_TOKEN, TELEGRAM_TOKEN)
```

## Configuration (env vars)

| Var | Module | Default | Description |
|-----|--------|---------|-------------|
| `WHISPER_MODELS` | core | `small,large-v3` | Comma-separated list of Whisper models to load |
| `HF_TOKEN` | core | — | HuggingFace token. Required for diarization |
| `HF_HOME` | core | `/models` | HF model cache path (mapped to `model-cache` volume) |
| `TELEGRAM_TOKEN` | bot | — | Telegram bot token from @BotFather |
| `DICTARA_URL` | bot | `http://dictara:8000` | Core service URL (auto-set in Docker) |

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
docker compose build core && docker compose up -d dictara
docker compose build bot  && docker compose up -d bot

# Check health
curl http://localhost:8000/health
```

## docker-compose services

| Service | Build context | Exposes |
|---------|--------------|---------|
| `dictara` | `./core` | `:8000` (HTTP API) |
| `bot` | `./bot` | — (outbound only, long-polling) |

Model downloads cache to the `model-cache` Docker volume. `docker compose down` keeps the volume; only `docker compose down -v` deletes it.
