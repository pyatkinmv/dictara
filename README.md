# Dictara

Local audio transcription service with optional speaker diarization. Runs entirely in Docker — no cloud APIs, no data leaving your machine (except HuggingFace model downloads on first start and optional Gemini summarization).

## Features

- Transcribe audio/video files via web UI or Telegram bot
- Speaker diarization (who said what)
- Two Whisper models: `small` (fast) and `large-v3` (accurate)
- Optional AI summarization via Google Gemini (`GEMINI_API_KEY`)
- Async job queue with live progress tracking
- Telegram-based web login with JWT authentication
- Transcription history per user in the web UI
- Supports mp3, mp4, m4a, wav, ogg, oga, opus, flac, webm, mkv, avi, mov

## Architecture

```
Browser ──────────────► gateway :8080 ──► transcriber :8000
                           │                   (Whisper + pyannote)
                           │
                         postgres

Telegram ──► telegram-bot-api ──► tg-bot ──► gateway :8080
```

| Module | Language | Role |
|--------|----------|------|
| `gateway/` | Kotlin / Spring Boot | HTTP entry point, auth, job persistence, orchestration |
| `transcriber/` | Python / FastAPI | Whisper transcription + pyannote diarization |
| `tg-bot/` | Kotlin | Telegram bot — audio in, transcript out |
| `app/` | Dart / Flutter | Web client (Material 3) |

## Quickstart

**1. Clone and configure**

```bash
git clone https://github.com/pyatkinmv/dictara.git
cd dictara
```

Create a `.env` file:

```env
# Required
TELEGRAM_TOKEN=your_telegram_bot_token
POSTGRES_PASSWORD=choose_a_password
JWT_SECRET=choose_a_long_random_secret

# Required for large files (>20 MB) via Telegram
TELEGRAM_API_ID=your_telegram_api_id
TELEGRAM_API_HASH=your_telegram_api_hash

# Required for web login (set to your bot's @username without @)
TELEGRAM_BOT_USERNAME=your_bot_username

# Required for diarization (speaker detection)
HF_TOKEN=your_huggingface_token

# Optional — enables AI summarization
GEMINI_API_KEY=your_gemini_api_key
```

> **HuggingFace setup (diarization):** Get a token at https://huggingface.co/settings/tokens, then accept the terms at:
> - https://huggingface.co/pyannote/speaker-diarization-3.1
> - https://huggingface.co/pyannote/segmentation-3.0

**2. Build and run**

```bash
docker compose build
docker compose up -d
```

First start downloads Whisper + diarization models (~4–5 GB total) to a persistent Docker volume. Subsequent starts load from cache.

**3. Use it**

- Web UI: http://localhost:3000
- Telegram bot: send any audio/video file to your bot

## Web UI

Open http://localhost:3000, log in with your Telegram username, then drop any audio or video file.

**Login flow:**
1. Click "Login with Telegram", enter your `@username`
2. If the bot knows you (you've chatted with it privately before): a confirmation message appears in Telegram — tap Confirm
3. If the bot doesn't know you yet: open the bot link, send `/start`, then confirm there

After login you get a history list of all your transcriptions below the drop zone. Each entry is expandable to show the full transcript and summary.

## Telegram Bot

Send any audio/video file to your bot — it transcribes and returns `transcript.txt`.

**Commands:**
- Send any audio/video file → receive transcript with live progress
- `/settings` → model (fast/accurate), diarization, summarization, language, speakers

## Docker services

| Service | Port | Description |
|---------|------|-------------|
| `gateway` | `8080` (localhost only) | Spring Boot API gateway |
| `transcriber` | `8000` | Python transcription service |
| `postgres` | `5432` | PostgreSQL — jobs, users, auth |
| `telegram-bot-api` | — | Local Telegram Bot API (large file support) |
| `tg-bot` | — | Telegram bot |
| `app-material` | `3000` | Flutter web — Material 3 |

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `TELEGRAM_TOKEN` | yes | Bot token from @BotFather |
| `POSTGRES_PASSWORD` | yes | PostgreSQL password |
| `JWT_SECRET` | yes | Secret for signing JWT tokens |
| `TELEGRAM_BOT_USERNAME` | yes | Bot username without `@` (for web login link) |
| `TELEGRAM_API_ID` | yes* | Telegram API ID for local bot API (*large files) |
| `TELEGRAM_API_HASH` | yes* | Telegram API hash (*large files) |
| `HF_TOKEN` | diarization | HuggingFace token for pyannote models |
| `GEMINI_API_KEY` | optional | Google Gemini key — enables summarization |
| `GEMINI_MODEL` | optional | Gemini model (default: `gemini-2.5-flash`) |
| `WHISPER_MODELS` | optional | Models to load (default: `small,large-v3`) |

## Performance (CPU, 4-minute recording)

| Model | Transcription | + Diarization |
|-------|--------------|---------------|
| `small` | ~3 min | ~8 min |
| `large-v3` | ~16 min | ~21 min |

GPU support is automatic when CUDA is available (10–20x faster).

## Self-hosting

**Requirements (minimal):** Linux server, 4 GB RAM, 30 GB disk, a domain.

**Add swap** (recommended — helps with builds and model loading on low-RAM machines):
```bash
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**Install Docker:**
```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker
```

**Clone, configure, and start** — same as local quickstart. First build takes 20–30 min on a low-end CPU.

**Reverse proxy + HTTPS** — [Caddy](https://caddyserver.com) handles TLS automatically via Let's Encrypt:
```bash
sudo apt install caddy

sudo tee /etc/caddy/Caddyfile <<'EOF'
your-domain.com {
    reverse_proxy localhost:3000
}
EOF
sudo systemctl reload caddy
```

**DNS:** Point an A record for your domain to the server's IP. Caddy obtains a certificate automatically once DNS propagates.
