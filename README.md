# Dictara

Local audio transcription service with optional speaker diarization. Runs entirely in Docker — no cloud APIs, no data leaving your machine.

## Features

- Transcribe audio/video files via REST API or Telegram bot
- Speaker diarization (who said what)
- Two Whisper models: `small` (fast) and `large-v3` (accurate)
- Optional AI summarization via Google Gemini (set `GEMINI_API_KEY`)
- Async job queue with live progress tracking
- Supports mp3, mp4, m4a, wav, ogg, flac, webm, mkv, avi, mov

## Quickstart

**1. Clone and configure**
```bash
git clone https://github.com/pyatkinmv/dictara.git
cd dictara
```

Create a `.env` file:
```
HF_TOKEN=your_huggingface_token
TELEGRAM_TOKEN=your_telegram_bot_token
GEMINI_API_KEY=your_gemini_api_key   # optional — enables summarization
```

> A HuggingFace token is required for diarization. Get one at https://huggingface.co/settings/tokens
> Then accept terms at: https://huggingface.co/pyannote/speaker-diarization-3.1 and https://huggingface.co/pyannote/segmentation-3.0

**2. Build and run**
```bash
docker compose build
docker compose up -d
```

First start downloads models (~4GB total) to a persistent Docker volume. Subsequent starts load from cache.

**3. Transcribe**
```bash
# Submit a job
curl -X POST "http://localhost:8000/transcribe?language=en&model=large-v3" \
  -F "file=@audio.mp3"
# → {"job_id": "abc-123"}

# Poll for result
curl http://localhost:8000/jobs/abc-123
# → {"status": "done", "result": {"segments": [...]}, "duration_s": 42.1,
#    "elapsed_s": null, "progress": {"processed_s": ..., "total_s": ..., "phase": "transcribing", "diarize_progress": null}}
```

## API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/transcribe` | POST | Submit transcription job |
| `/jobs/{job_id}` | GET | Poll job status and result |
| `/health` | GET | Service health and loaded models |

**`/transcribe` query params:**

| Param | Default | Description |
|-------|---------|-------------|
| `model` | `small` | `small` (fast) or `large-v3` (accurate) |
| `language` | auto | Language code: `en`, `ru`, `de`, etc. |
| `diarize` | `false` | Add speaker labels to segments |
| `num_speakers` | auto | Exact speaker count hint for diarization (e.g. `2`, `3`) |

**Segment format:**
```json
{"start": 0.0, "end": 2.4, "text": "Hello world", "speaker": "SPEAKER_00"}
```
(`speaker` only present when `diarize=true`)

## Performance (CPU, 4-minute recording)

| Model | Transcription | + Diarization |
|-------|--------------|---------------|
| `small` | ~3 min | ~8 min |
| `large-v3` | ~16 min | ~21 min |

GPU support is automatic when CUDA is available (10-20x faster).

## Telegram Bot

Send an audio file to your bot — it transcribes and returns `transcript.txt`. Uses accurate model + diarization by default.

**Setup:**
1. Create a bot via [@BotFather](https://t.me/BotFather), copy the token
2. Add `TELEGRAM_TOKEN=...` (and optionally `GEMINI_API_KEY=...`) to `.env`
3. `docker compose up -d bot`

**Commands:**
- Send any audio/video file → receive `transcript.txt` with live progress updates
- `/settings` → change model (fast / accurate), diarization (on / off), summarization (on / off), language, number of speakers
