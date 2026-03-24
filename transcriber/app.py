import asyncio
import logging
import math
import os
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, UploadFile, File, Query

from jobs import job_store
from transcriber import Transcriber, Diarizer, merge_diarization

from prometheus_client import Gauge, REGISTRY
from prometheus_fastapi_instrumentator import Instrumentator

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Single worker — WhisperModel is not thread-safe for concurrent inference
_executor = ThreadPoolExecutor(max_workers=1)

_CHUNK_THRESHOLD_S = 2400   # 40 min — only chunk if longer than this
_CHUNK_SIZE_S      = 1200   # 20 min per chunk


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.transcribers = {}
    app.state.diarizer = None
    models_env = os.environ.get("WHISPER_MODELS", "small,turbo")
    for model_size in [m.strip() for m in models_env.split(",") if m.strip()]:
        try:
            app.state.transcribers[model_size] = Transcriber(model_size=model_size)
            logger.info("Transcriber ready (model=%s)", model_size)
        except Exception:
            logger.exception("Failed to load Whisper model %s", model_size)

    if os.environ.get("HF_TOKEN"):
        try:
            app.state.diarizer = Diarizer()
        except Exception:
            logger.exception("Failed to load diarization pipeline — diarize=true will be unavailable")

    yield
    _executor.shutdown(wait=False)


app = FastAPI(title="Dictara Transcription API", lifespan=lifespan)

_build_info = Gauge("build_info", "Build metadata", ["git_commit", "build_time"])
_build_info.labels(
    git_commit=os.environ.get("GIT_COMMIT", "unknown"),
    build_time=os.environ.get("BUILD_TIME", "unknown"),
).set(1)

Instrumentator(registry=REGISTRY).instrument(app).expose(app)


# ── chunking helpers ──────────────────────────────────────────────────────────

def _get_duration(wav_path: str) -> float:
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", wav_path],
        check=True, capture_output=True, text=True,
    )
    return float(result.stdout.strip())


def _transcribe_chunked(
    job_id: str,
    wav_path: str,
    transcriber,
    language: str | None,
    total_s: float,
) -> list[dict]:
    """Split wav_path into _CHUNK_SIZE_S-second chunks, transcribe each,
    return segments with timestamps offset to absolute positions."""
    all_segments = []
    starts = list(range(0, int(total_s), _CHUNK_SIZE_S))

    for i, chunk_start in enumerate(starts):
        chunk_path = f"{wav_path}.chunk{chunk_start}.wav"
        logger.info(
            "Chunk %d/%d: extracting %.0f–%.0f s",
            i + 1, len(starts), chunk_start, min(chunk_start + _CHUNK_SIZE_S, total_s),
        )
        subprocess.run(
            ["ffmpeg", "-y", "-i", wav_path,
             "-ss", str(chunk_start), "-t", str(_CHUNK_SIZE_S),
             chunk_path],
            check=True, capture_output=True,
        )

        chunk_offset = float(chunk_start)

        def on_segment(processed_s: float, chunk_duration_s: float,
                       _offset=chunk_offset):
            job_store.set_progress(job_id, _offset + processed_s, total_s)

        t0 = time.time()
        try:
            chunk_segs = transcriber.transcribe(
                chunk_path, language=language, progress_callback=on_segment
            )
        finally:
            try:
                os.unlink(chunk_path)
            except OSError:
                pass

        logger.info(
            "Chunk %d/%d: done in %.1fs — %d segments",
            i + 1, len(starts), time.time() - t0, len(chunk_segs),
        )
        for seg in chunk_segs:
            all_segments.append({
                **seg,
                "start": seg["start"] + chunk_offset,
                "end":   seg["end"]   + chunk_offset,
            })

    return all_segments


# ── worker ────────────────────────────────────────────────────────────────────

def _run_transcription(job_id: str, tmp_path: str, language: str | None, diarize: bool, model: str, num_speakers: int | None) -> None:
    """Blocking — always runs inside ThreadPoolExecutor."""
    transcriber: Transcriber = app.state.transcribers[model]
    job_store.set_processing(job_id)
    wav_path = None
    try:
        # Convert to WAV so pyannote (soundfile) can read any input format
        wav_path = tmp_path + ".wav"
        try:
            result = subprocess.run(
                ["ffmpeg", "-y", "-i", tmp_path, "-ar", "16000", "-ac", "1", wav_path],
                check=True, capture_output=True,
            )
        except subprocess.CalledProcessError as e:
            stderr = e.stderr.decode(errors="replace")
            if "no streams" in stderr or "does not contain" in stderr or "match no streams" in stderr:
                job_store.set_failed(job_id, "File has no audio track.", retryable=False)
                return
            job_store.set_failed(job_id, "Could not decode the file — it may be corrupted or not a valid audio/video file.", retryable=False)
            return
        total_s = _get_duration(wav_path)
        if total_s > _CHUNK_THRESHOLD_S:
            n_chunks = math.ceil(total_s / _CHUNK_SIZE_S)
            logger.info(
                "Long audio detected: %.0fs (%.1f min) — splitting into %d chunks of %ds",
                total_s, total_s / 60, n_chunks, _CHUNK_SIZE_S,
            )
            segments = _transcribe_chunked(job_id, wav_path, transcriber, language, total_s)
            logger.info("Chunked transcription complete: %d segments total", len(segments))
        else:
            def on_segment(processed_s: float, ts: float):
                job_store.set_progress(job_id, processed_s, ts)
            segments = transcriber.transcribe(wav_path, language=language, progress_callback=on_segment)
        if diarize and app.state.diarizer is not None:
            job_store.set_diarizing(job_id)
            def on_diarize(completed: int, total: int):
                job_store.set_diarize_progress(job_id, completed, total)
            diarization = app.state.diarizer.diarize(wav_path, num_speakers=num_speakers, progress_callback=on_diarize)
            segments = merge_diarization(segments, diarization)
        job_store.set_done(job_id, segments)
    except Exception as exc:
        logger.exception("Transcription failed for job %s", job_id)
        job_store.set_failed(job_id, str(exc))
    finally:
        for path in (tmp_path, wav_path):
            try:
                if path:
                    os.unlink(path)
            except OSError:
                pass


async def _dispatch(job_id: str, tmp_path: str, language: str | None, diarize: bool, model: str, num_speakers: int | None) -> None:
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(_executor, _run_transcription, job_id, tmp_path, language, diarize, model, num_speakers)


# ── routes ────────────────────────────────────────────────────────────────────

@app.post("/transcribe", status_code=202)
async def transcribe(
    file: UploadFile = File(...),
    language: str | None = Query(default=None),
    diarize: bool = Query(default=False),
    model: str = Query(default="small"),
    num_speakers: int | None = Query(default=None),
):
    if model not in app.state.transcribers:
        raise HTTPException(status_code=400, detail=f"Model '{model}' not loaded. Available: {list(app.state.transcribers)}")
    if diarize and app.state.diarizer is None:
        raise HTTPException(status_code=503, detail="Diarization unavailable: HF_TOKEN not configured or pipeline failed to load")

    suffix = os.path.splitext(file.filename or "audio")[1] or ".tmp"
    with tempfile.NamedTemporaryFile(dir="/tmp", suffix=suffix, delete=False) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name

    job_id = job_store.create(tmp_path)
    asyncio.create_task(_dispatch(job_id, tmp_path, language, diarize, model, num_speakers))
    return {"job_id": job_id}


@app.get("/jobs/{job_id}")
async def get_job(job_id: str):
    job = job_store.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    duration = round(job.finished_at - job.started_at, 1) if job.finished_at and job.started_at else None
    elapsed_s = round(time.time() - job.started_at, 1) if job.started_at and job.status == "processing" else None
    progress = None
    if job.total_s and job.progress_s is not None:
        progress = {
            "processed_s": round(job.progress_s, 1),
            "total_s": round(job.total_s, 1),
            "phase": job.phase,
            "diarize_progress": job.diarize_progress,
        }
    return {"status": job.status, "result": job.result, "error": job.error, "retryable": job.retryable, "duration_s": duration, "elapsed_s": elapsed_s, "progress": progress}


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "models_loaded": list(getattr(app.state, "transcribers", {}).keys()),
        "diarization_available": getattr(app.state, "diarizer", None) is not None,
    }
