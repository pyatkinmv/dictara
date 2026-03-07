# Dictara

Local audio transcription service with optional speaker diarization. Runs entirely in Docker — no cloud APIs, no data leaving your machine.

## Features

- Transcribe audio/video files via REST API
- Speaker diarization (who said what)
- Two Whisper models loaded simultaneously: `small` (fast) and `large-v3` (accurate)
- Async job queue — submit and poll
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
# → {"status": "done", "result": {"segments": [...]}, "duration_s": 42.1}
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
