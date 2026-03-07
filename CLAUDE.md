# Dictara — Local Audio Transcription Service

## What it does
FastAPI service that transcribes audio/video files using Whisper and optionally adds speaker diarization (who said what). Runs fully locally in Docker, no external APIs needed (except HuggingFace model download on first start).

## Architecture

```
POST /transcribe  →  saves file to /tmp  →  creates job  →  dispatches to ThreadPoolExecutor
                                                                        ↓
                                                            _run_transcription()
                                                            1. ffmpeg: any format → WAV (16kHz mono)
                                                            2. Whisper: WAV → segments [{start, end, text}]
                                                            3. pyannote (if diarize=true): WAV → speaker turns
                                                            4. merge: assigns speaker to each segment
                                                                        ↓
GET /jobs/{id}   ←  job_store (in-memory)  ←  set_done(segments)
```

Single-worker executor — WhisperModel is not thread-safe for concurrent inference.

## Key files

| File | Purpose |
|------|---------|
| `app.py` | FastAPI routes, lifespan (model loading), job dispatch |
| `transcriber.py` | `Transcriber` (Whisper), `Diarizer` (pyannote), `merge_diarization()` |
| `jobs.py` | In-memory job store with status + timing |
| `Dockerfile` | Multi-stage: static ffmpeg + python:3.12-slim |
| `docker-compose.yml` | Single service, `model-cache` volume for HF models |
| `requirements.txt` | Pinned deps — see constraints below |

## API

```bash
# Submit job (returns job_id immediately, 202)
curl -X POST "http://localhost:8000/transcribe?language=ru&diarize=true" \
  -F "file=@audio.m4a"

# Poll result
curl http://localhost:8000/jobs/{job_id}
# → {"status": "done", "result": {"segments": [...]}, "duration_s": 156.3, "error": null}

# Health check
curl http://localhost:8000/health
# → {"status": "ok", "model_loaded": true, "diarization_available": true}
```

Segment shape (without diarization): `{"start": 0.0, "end": 2.4, "text": "Hello"}`
Segment shape (with diarization): `{"start": 0.0, "end": 2.4, "text": "Hello", "speaker": "SPEAKER_00"}`

## Configuration (env vars)

| Var | Default | Description |
|-----|---------|-------------|
| `WHISPER_MODEL` | `small` | Model size: tiny / base / small / medium / large-v3 |
| `HF_TOKEN` | — | HuggingFace token. Required for diarization. Set in `.env` |
| `HF_HOME` | `/models` | Where HF caches models (mapped to Docker volume) |

## Dependency constraints (important)

- `pyannote.audio>=2.1,<4.0` — v4.x pulls in `torchcodec` which requires system ffmpeg shared libs (we only have the static binary). v3.x works fine.
- `torch==2.3.1` + `torchaudio==2.3.1` — pyannote 3.x uses `torchaudio.AudioMetaData` which was removed in torchaudio 2.4+. Pin to 2.3.1.
- `huggingface_hub` resolves to 1.x — pyannote 3.x calls `hf_hub_download(use_auth_token=...)` which was removed in huggingface_hub 1.0. Fixed via monkey-patch in `transcriber.py:_patch_hf_hub_compat()`.

## Diarization setup

Two HuggingFace gated models require manual acceptance at:
- https://huggingface.co/pyannote/speaker-diarization-3.1
- https://huggingface.co/pyannote/segmentation-3.0

Then set `HF_TOKEN` in `.env`. Models download ~1GB on first container start (cached in `model-cache` volume).

## Whisper transcription settings (transcriber.py)

```python
self.model.transcribe(
    audio_path,
    language=language,   # e.g. "ru". None = auto-detect
    beam_size=10,        # higher = more accurate, slower. 1=greedy, 5=default, 10=current
    vad_filter=True,     # skip silent chunks before Whisper sees them (prevents hallucination)
)
```

Key parameters to tune:
- `beam_size` — 5 is default, 10 is current setting (slightly better accuracy, ~10% slower)
- `vad_filter=True` — prevents Whisper from hallucinating repeated text during silence
- `condition_on_previous_text=False` — breaks echo loops when Whisper repeats itself across chunks (disabled currently, re-enable if loops appear)
- `no_speech_threshold` — default 0.6, lower = stricter silence filtering
- `log_prob_threshold` — default -1.0, raise to -0.5 to drop low-confidence segments

## Performance (CPU, ~4-minute recording)

| Mode | Time |
|------|------|
| Whisper only (beam_size=10) | ~2.6 min |
| Whisper + diarization | ~7.8 min |

GPU would be 10-20x faster. Set `WHISPER_MODEL=medium` for better accuracy at ~2x slower.

## Build & run

```bash
# First time (or after requirements.txt change): ~10 min, downloads ~2GB
docker compose build --progress=plain

# Start
docker compose up -d

# Rebuild after code changes (fast, pip layer cached)
docker compose build && docker compose down && docker compose up -d
```

BuildKit pip cache (`--mount=type=cache,target=/root/.cache/pip`) persists wheels across builds — only changed packages re-download.

## Audio format notes

All uploaded files are converted to WAV (16kHz mono) via ffmpeg before processing. This is required because pyannote uses `soundfile` which only supports WAV/FLAC/OGG natively. Supported input formats: mp3, mp4, m4a, wav, ogg, flac, webm, mkv, avi, mov.
