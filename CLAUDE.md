# Dictara — Local Audio Transcription Service

## What it does
Transcribes audio/video files using Whisper with optional speaker diarization. Gateway, bot, and web app run in Docker on a VM; the transcriber runs on Google Cloud Run (GPU). The gateway is the single HTTP entry point; the transcriber does the actual Whisper work; tg-bot handles Telegram; the Flutter web client provides a browser UI.

See [docs/deployment.md](docs/deployment.md) for production infrastructure details (Cloud Run, GCS, CloudFlare).

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
| `DICTARY_BASE_URL` | tg-bot | `https://dictary.app` | Public base URL — used in web login links sent to users |
| `TRANSCRIBER_URL` | gateway | `http://transcriber:8000` | Transcriber URL — override to Cloud Run URL in production |
| `GCS_UPLOADS_BUCKET` | gateway | — | GCS bucket for audio uploads — when set, large files are sent to the transcriber by `gs://` reference instead of streamed over HTTP (works around Cloud Run's 32 MiB request body limit). Empty = legacy in-DB BLOB path, used locally |
| `GEMINI_API_KEY` | gateway | — | Google Gemini key — enables summarization |
| `GEMINI_MODEL` | gateway | `gemini-2.5-flash` | Gemini model to use |
| `SUMMARIZER_PROVIDER` | gateway | `gemini` | Summarization backend |
| `CF_ZONE_ID` | CI only | — | CloudFlare Zone ID for dictary.app — used by CI to purge CDN cache after `app-material` deploys |
| `CF_API_TOKEN` | CI only | — | CloudFlare API token with Zone / Cache Purge right — stored as GitHub Actions secret |

## Build & run

```bash
# Start everything (transcriber NOT included — it runs on Cloud Run in production)
docker compose up -d

# Include local transcriber (first run downloads ~4–5 GB of models, takes ~15 min)
docker compose --profile local up -d

# Rebuild a single module after code changes
docker compose build gateway      && docker compose up -d gateway
docker compose build tg-bot       && docker compose up -d tg-bot
docker compose build app-material && docker compose up -d app-material

# Rebuild transcriber (local profile only)
docker compose --profile local build transcriber && docker compose --profile local up -d transcriber

# Check health
curl http://localhost:8080/health
```

## docker-compose services

| Service | Build context | Exposes | Notes |
|---------|--------------|---------|-------|
| `gateway` | `./gateway` | `:8080` (localhost only) | Spring Boot; depends on postgres |
| `postgres` | image `postgres:16` | `:5432` | Persistent volume `postgres-data` |
| `telegram-bot-api` | image `aiogram/telegram-bot-api` | — | Local Bot API for >20 MB files |
| `tg-bot` | `./tg-bot` | — | Outbound only; depends on gateway |
| `app-material` | `./app` | `:3000` | Flutter web — Material 3; behind CloudFlare CDN |
| `transcriber` | `./transcriber` | `:8000` | **`profiles: [local]` — not started by default.** In production the transcriber runs on Cloud Run; use `--profile local` for local dev |
| `prometheus` | image `prom/prometheus` | `:9091` (localhost) | Metrics scraping |
| `loki` | image `grafana/loki` | `:3100` (localhost) | Log aggregation |
| `promtail` | image `grafana/promtail` | — | Ships Docker container logs to Loki |
| `node-exporter` | image `prom/node-exporter` | — | Host metrics for Prometheus |

Model downloads (when running transcriber locally) cache to the `model-cache` Docker volume. `docker compose down` keeps volumes; only `docker compose down -v` deletes them.

## Resource limits

`scripts/set-limits.sh` calculates transcriber memory/CPU limits as a percentage of the host machine's resources and writes them to `.env.limits`. Run it before `docker compose up` to apply dynamic limits:

```bash
bash scripts/set-limits.sh
set -a; source .env.limits; set +a
docker compose up -d transcriber
```

CI runs this automatically on every deploy. Without it, `docker-compose.yml` falls back to hardcoded defaults.
