import asyncio
import logging
import multiprocessing
import os
import queue
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, UploadFile, File, Query

from jobs import job_store
from transcriber import Transcriber, Diarizer, merge_diarization

from prometheus_client import Gauge
from prometheus_fastapi_instrumentator import Instrumentator

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

_build_info = Gauge("build_info", "Build metadata", ["git_commit", "build_time"])
_build_info.labels(
    git_commit=os.environ.get("GIT_COMMIT", "unknown"),
    build_time=os.environ.get("BUILD_TIME", "unknown"),
).set(1)

Instrumentator().instrument(app).expose(app)


# ── subprocess worker (runs in child process after fork) ───────────────────────

def _transcription_worker(
    job_id: str,
    tmp_path: str,
    language: str | None,
    diarize: bool,
    model: str,
    num_speakers: int | None,
    progress_queue: multiprocessing.Queue,
) -> None:
    """
    Runs in a child process forked from the parent.

    Because we use fork (Linux default), this process inherits app.state.transcribers
    and app.state.diarizer — model objects are already loaded, no reload needed.

    All results and intermediate progress are communicated back to the parent via
    progress_queue using typed dicts:
      {"type": "processing"}
      {"type": "progress", "processed_s": float, "total_s": float}
      {"type": "diarizing"}
      {"type": "diarize_progress", "completed": int, "total": int}
      {"type": "done", "segments": [...]}
      {"type": "failed", "error": str, "retryable": bool}
    """
    logger.info("Worker started: job_id=%s model=%s diarize=%s pid=%d", job_id, model, diarize, os.getpid())
    transcriber: Transcriber = app.state.transcribers[model]
    wav_path = None
    try:
        progress_queue.put({"type": "processing"})
        logger.info("Worker sent processing: job_id=%s", job_id)

        # Convert to WAV so pyannote (soundfile) can read any input format
        wav_path = tmp_path + ".wav"
        try:
            subprocess.run(
                ["ffmpeg", "-y", "-i", tmp_path, "-ar", "16000", "-ac", "1", wav_path],
                check=True, capture_output=True,
            )
            logger.info("Worker ffmpeg done: job_id=%s wav=%s", job_id, wav_path)
        except subprocess.CalledProcessError as e:
            stderr = e.stderr.decode(errors="replace")
            logger.warning("Worker ffmpeg failed: job_id=%s stderr=%s", job_id, stderr[:200])
            if "no streams" in stderr or "does not contain" in stderr or "match no streams" in stderr:
                progress_queue.put({"type": "failed", "error": "File has no audio track.", "retryable": False})
            else:
                progress_queue.put({"type": "failed", "error": "Could not decode the file — it may be corrupted or not a valid audio/video file.", "retryable": False})
            return

        def on_segment(processed_s: float, total_s: float):
            progress_queue.put({"type": "progress", "processed_s": processed_s, "total_s": total_s})

        segments = transcriber.transcribe(wav_path, language=language, progress_callback=on_segment)
        logger.info("Worker transcription done: job_id=%s segments=%d", job_id, len(segments))

        if diarize and app.state.diarizer is not None:
            progress_queue.put({"type": "diarizing"})
            logger.info("Worker sent diarizing: job_id=%s", job_id)

            def on_diarize(completed: int, total: int):
                progress_queue.put({"type": "diarize_progress", "completed": completed, "total": total})

            diarization = app.state.diarizer.diarize(wav_path, num_speakers=num_speakers, progress_callback=on_diarize)
            segments = merge_diarization(segments, diarization)
            logger.info("Worker diarization done: job_id=%s", job_id)

        progress_queue.put({"type": "done", "segments": segments})
        logger.info("Worker sent done: job_id=%s segments=%d", job_id, len(segments))

    except Exception as exc:
        logger.exception("Worker uncaught exception: job_id=%s", job_id)
        progress_queue.put({"type": "failed", "error": str(exc), "retryable": True})
    finally:
        for path in (tmp_path, wav_path):
            try:
                if path:
                    os.unlink(path)
                    logger.debug("Worker cleaned up: %s", path)
            except OSError:
                pass


# ── parent-side helpers ────────────────────────────────────────────────────────

def _drain_queue(job_id: str, progress_queue: multiprocessing.Queue) -> None:
    """Read all currently-available messages from the queue and apply them to job_store."""
    while True:
        try:
            msg = progress_queue.get_nowait()
        except queue.Empty:
            break
        msg_type = msg.get("type")
        if msg_type == "processing":
            job_store.set_processing(job_id)
        elif msg_type == "progress":
            job_store.set_progress(job_id, msg["processed_s"], msg["total_s"])
        elif msg_type == "diarizing":
            job_store.set_diarizing(job_id)
        elif msg_type == "diarize_progress":
            job_store.set_diarize_progress(job_id, msg["completed"], msg["total"])
        elif msg_type == "done":
            job_store.set_done(job_id, msg["segments"])
        elif msg_type == "failed":
            job_store.set_failed(job_id, msg["error"], retryable=msg.get("retryable", True))
        else:
            logger.warning("Unexpected queue message type: job_id=%s type=%s", job_id, msg_type)


def _run_transcription_subprocess(
    job_id: str,
    tmp_path: str,
    language: str | None,
    diarize: bool,
    model: str,
    num_speakers: int | None,
) -> None:
    """
    Blocking — always runs inside ThreadPoolExecutor.

    Spawns a child process that performs the actual transcription work. The parent
    monitors the child's progress queue and checks for cancellation every 0.1s.

    Cancellation: when job.cancel_requested is set (by the cancel endpoint), the parent
    sends SIGTERM to the child, waits up to 3 seconds, then SIGKILL if still alive.
    """
    # Use fork on Linux (default, shares loaded model memory with child at no cost).
    # Fall back to spawn on platforms where fork is unavailable (e.g. Windows, macOS default).
    _start_method = "fork" if "fork" in multiprocessing.get_all_start_methods() else "spawn"
    ctx = multiprocessing.get_context(_start_method)
    progress_queue = ctx.Queue()
    process = ctx.Process(
        target=_transcription_worker,
        args=(job_id, tmp_path, language, diarize, model, num_speakers, progress_queue),
    )
    process.start()
    logger.info("Subprocess started: job_id=%s pid=%d", job_id, process.pid)

    while process.is_alive():
        _drain_queue(job_id, progress_queue)

        job = job_store.get(job_id)
        if job and job.cancel_requested:
            logger.info("Cancel requested — sending SIGTERM: job_id=%s pid=%d", job_id, process.pid)
            process.terminate()
            process.join(timeout=3)
            if process.is_alive():
                logger.warning("Process did not exit after SIGTERM — sending SIGKILL: job_id=%s pid=%d", job_id, process.pid)
                process.kill()
                process.join()
            logger.info("Subprocess killed, setting cancelled: job_id=%s", job_id)
            job_store.set_cancelled(job_id)
            return

        time.sleep(0.1)

    # Drain any final messages posted just before the process exited
    _drain_queue(job_id, progress_queue)
    process.join()
    logger.info("Subprocess finished naturally: job_id=%s exitcode=%s", job_id, process.exitcode)

    # Guard: if the child exited without sending a terminal status message (e.g. segfault,
    # OOM kill), mark the job failed so it does not get stuck in "processing" indefinitely.
    job = job_store.get(job_id)
    if job and job.status == "processing":
        logger.error(
            "Subprocess exited without terminal status: job_id=%s exitcode=%s",
            job_id, process.exitcode,
        )
        job_store.set_failed(job_id, f"Process exited unexpectedly (exit code {process.exitcode})")


async def _dispatch(
    job_id: str,
    tmp_path: str,
    language: str | None,
    diarize: bool,
    model: str,
    num_speakers: int | None,
) -> None:
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(
        _executor, _run_transcription_subprocess, job_id, tmp_path, language, diarize, model, num_speakers
    )


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
    with tempfile.NamedTemporaryFile(dir=tempfile.gettempdir(), suffix=suffix, delete=False) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name

    job_id = job_store.create(tmp_path)
    logger.info("Job created: job_id=%s model=%s diarize=%s language=%s", job_id, model, diarize, language)
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


@app.post("/jobs/{job_id}/cancel", status_code=200)
async def cancel_job(job_id: str):
    """
    Request cancellation of a running or pending job.

    Returns {"cancelled": true} if the job was in a cancellable state (pending or processing)
    and the cancel flag was set. The actual subprocess kill happens asynchronously in the
    monitoring loop — poll GET /jobs/{id} until status becomes "cancelled".

    Returns {"cancelled": false} if the job has already reached a terminal state
    (done, failed, cancelled) or does not exist (404).
    """
    job = job_store.get(job_id)
    if job is None:
        logger.warning("Cancel request for unknown job: job_id=%s", job_id)
        raise HTTPException(status_code=404, detail="Job not found")
    cancelled = job_store.request_cancel(job_id)
    logger.info("Cancel requested: job_id=%s status=%s cancelled=%s", job_id, job.status, cancelled)
    return {"cancelled": cancelled}


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "models_loaded": list(getattr(app.state, "transcribers", {}).keys()),
        "diarization_available": getattr(app.state, "diarizer", None) is not None,
    }
