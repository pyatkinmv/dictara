import asyncio
import logging
import os
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, UploadFile, File, Query

from jobs import job_store
from transcriber import Transcriber, Diarizer, merge_diarization

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Single worker — WhisperModel is not thread-safe for concurrent inference
_executor = ThreadPoolExecutor(max_workers=1)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.transcribers = {}
    app.state.diarizer = None
    models_env = os.environ.get("WHISPER_MODELS", "small,large-v3")
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


# ── worker ────────────────────────────────────────────────────────────────────

def _run_transcription(job_id: str, tmp_path: str, language: str | None, diarize: bool, model: str, num_speakers: int | None) -> None:
    """Blocking — always runs inside ThreadPoolExecutor."""
    transcriber: Transcriber = app.state.transcribers[model]
    job_store.set_processing(job_id)
    wav_path = None
    try:
        # Convert to WAV so pyannote (soundfile) can read any input format
        wav_path = tmp_path + ".wav"
        subprocess.run(
            ["ffmpeg", "-y", "-i", tmp_path, "-ar", "16000", "-ac", "1", wav_path],
            check=True, capture_output=True,
        )
        def on_segment(processed_s: float, total_s: float):
            job_store.set_progress(job_id, processed_s, total_s)

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
    return {"status": job.status, "result": job.result, "error": job.error, "duration_s": duration, "elapsed_s": elapsed_s, "progress": progress}


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "models_loaded": list(getattr(app.state, "transcribers", {}).keys()),
        "diarization_available": getattr(app.state, "diarizer", None) is not None,
    }
