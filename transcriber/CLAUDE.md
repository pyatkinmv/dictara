# transcriber — Python Transcription Service

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

Single-worker executor — WhisperModel is not thread-safe for concurrent inference. Jobs queue up and run one at a time.

## Key files

| File | Purpose |
|------|---------|
| `app.py` | FastAPI routes, lifespan (model loading), job dispatch |
| `transcriber.py` | `Transcriber` (Whisper), `Diarizer` (pyannote), `merge_diarization()` |
| `jobs.py` | In-memory job store with status, timing, and live progress fields |
| `Dockerfile` | Multi-stage: static ffmpeg + python:3.12-slim |
| `requirements.txt` | Pinned deps — see constraints below |

## API

```bash
# Submit job (returns job_id immediately, HTTP 202)
curl -X POST "http://localhost:8000/transcribe?language=ru&diarize=true&model=large-v3" \
  -F "file=@audio.m4a"
# → {"job_id": "abc-123"}

# Poll result (while processing)
curl http://localhost:8000/jobs/{job_id}
# → {"status": "processing", "result": null, "error": null, "duration_s": null,
#    "elapsed_s": 43.2,
#    "progress": {"processed_s": 87.4, "total_s": 240.0, "phase": "transcribing", "diarize_progress": null}}

# Poll result (done)
curl http://localhost:8000/jobs/{job_id}
# → {"status": "done", "result": {"segments": [...]}, "duration_s": 168.0, "error": null,
#    "elapsed_s": null, "progress": {"processed_s": 240.0, "total_s": 240.0, "phase": "transcribing", "diarize_progress": null}}
# status values: pending | processing | done | failed
# phase values: "transcribing" | "diarizing"

# Health check
curl http://localhost:8000/health
# → {"status": "ok", "models_loaded": ["small", "large-v3"], "diarization_available": true}
```

**Query params for `/transcribe`:**

| Param | Default | Description |
|-------|---------|-------------|
| `language` | auto-detect | Language code e.g. `ru`, `en`. Explicit is faster and more accurate |
| `diarize` | `false` | Add speaker labels (SPEAKER_00, SPEAKER_01, ...) |
| `model` | `small` | Which Whisper model to use: `small` or `large-v3` |

**Segment shape** (without diarization): `{"start": 0.0, "end": 2.4, "text": "Hello"}`
**Segment shape** (with diarization): `{"start": 0.0, "end": 2.4, "text": "Hello", "speaker": "SPEAKER_00"}`

## Whisper transcription settings

```python
self.model.transcribe(
    audio_path,
    language=language,   # e.g. "ru". None = auto-detect per chunk (slower, less accurate)
    beam_size=10,        # 1=greedy (fastest), 5=default, 10=current (better accuracy)
    vad_filter=True,     # pre-filter silence — prevents hallucination on quiet chunks
)
```

## Model caching

All models (Whisper + pyannote) download to the `model-cache` Docker volume on first start and are reused on every subsequent start. `docker compose down` keeps the volume intact. Only `docker compose down -v` deletes it.

- `small`: ~500MB, downloads in ~1 min
- `large-v3`: ~3GB, downloads in ~5 min
- pyannote diarization models: ~1GB

## Performance (CPU, ~4-minute recording)

| Model | Whisper only | Whisper + diarization |
|-------|-------------|----------------------|
| `small` | ~2.8 min | ~7.8 min |
| `large-v3` | ~16 min | ~21 min |

GPU would be 10-20x faster overall.

**Quality difference:** large-v3 makes significantly fewer word-level errors — proper nouns, technical terms, and unclear speech are handled much better.

## Dependency constraints (important)

- `pyannote.audio>=2.1,<4.0` — v4.x pulls in `torchcodec` which requires system ffmpeg shared libs (we only have the static binary). v3.x works fine.
- `torch==2.3.1` + `torchaudio==2.3.1` — pyannote 3.x uses `torchaudio.AudioMetaData` removed in torchaudio 2.4+.
- `huggingface_hub` 1.x removed `use_auth_token` kwarg that pyannote 3.x still passes. Fixed via monkey-patch in `transcriber.py:_patch_hf_hub_compat()`.

## Diarization setup

Two HuggingFace gated models require manual one-time acceptance:
- https://huggingface.co/pyannote/speaker-diarization-3.1
- https://huggingface.co/pyannote/segmentation-3.0

Then set `HF_TOKEN` in root `.env`. Models download on first start and cache to the volume.

## Audio format notes

All uploads are converted to 16kHz mono WAV via ffmpeg before processing (required because pyannote's `soundfile` only reads WAV/FLAC/OGG). Supported input: mp3, mp4, m4a, wav, ogg, flac, webm, mkv, avi, mov.

## Build

```bash
# From repo root:
docker compose build transcriber
docker compose up -d transcriber
```
