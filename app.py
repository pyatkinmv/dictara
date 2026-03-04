import asyncio
import logging
import os
import tempfile
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, UploadFile, File, Query

from jobs import job_store
from transcriber import Transcriber

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Single worker — WhisperModel is not thread-safe for concurrent inference
_executor = ThreadPoolExecutor(max_workers=1)


@asynccontextmanager
async def lifespan(app: FastAPI):
    model_size = os.environ.get("WHISPER_MODEL", "small")
    app.state.model_loaded = False
    try:
        app.state.transcriber = Transcriber(model_size=model_size)
        app.state.model_loaded = True
        logger.info("Transcriber ready (model=%s)", model_size)
    except Exception:
        logger.exception("Failed to load Whisper model")
    yield
    _executor.shutdown(wait=False)


app = FastAPI(title="Dictara Transcription API", lifespan=lifespan)


# ── worker ────────────────────────────────────────────────────────────────────

def _run_transcription(job_id: str, tmp_path: str, language: str | None) -> None:
    """Blocking — always runs inside ThreadPoolExecutor."""
    transcriber: Transcriber = app.state.transcriber
    job_store.set_processing(job_id)
    try:
        segments = transcriber.transcribe(tmp_path, language=language)
        job_store.set_done(job_id, segments)
    except Exception as exc:
        logger.exception("Transcription failed for job %s", job_id)
        job_store.set_failed(job_id, str(exc))
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


async def _dispatch(job_id: str, tmp_path: str, language: str | None) -> None:
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(_executor, _run_transcription, job_id, tmp_path, language)


# ── routes ────────────────────────────────────────────────────────────────────

@app.post("/transcribe", status_code=202)
async def transcribe(
    file: UploadFile = File(...),
    language: str | None = Query(default=None),
):
    suffix = os.path.splitext(file.filename or "audio")[1] or ".tmp"
    with tempfile.NamedTemporaryFile(dir="/tmp", suffix=suffix, delete=False) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name

    job_id = job_store.create(tmp_path)
    asyncio.create_task(_dispatch(job_id, tmp_path, language))
    return {"job_id": job_id}


@app.get("/jobs/{job_id}")
async def get_job(job_id: str):
    job = job_store.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return {"status": job.status, "result": job.result, "error": job.error}


@app.get("/health")
async def health():
    return {"status": "ok", "model_loaded": getattr(app.state, "model_loaded", False)}
