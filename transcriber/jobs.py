import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Literal

JobStatus = Literal["pending", "processing", "done", "failed"]


@dataclass
class Job:
    job_id: str
    status: JobStatus = "pending"
    result: dict | None = None   # {"segments": [...]} when done
    error: str | None = None
    retryable: bool = True
    tmp_path: str | None = None  # path to uploaded temp file
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    progress_s: float | None = None   # last Whisper-processed audio second
    total_s: float | None = None      # total audio duration
    phase: str = "transcribing"       # "transcribing" | "diarizing"
    diarize_progress: float | None = None  # 0.0–1.0 fraction


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = threading.Lock()

    def create(self, tmp_path: str) -> str:
        job_id = str(uuid.uuid4())
        with self._lock:
            self._jobs[job_id] = Job(job_id=job_id, tmp_path=tmp_path)
        return job_id

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def set_processing(self, job_id: str) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = "processing"
            job.started_at = time.time()

    def set_progress(self, job_id: str, progress_s: float, total_s: float) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.progress_s = progress_s
            job.total_s = total_s

    def set_diarizing(self, job_id: str) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.phase = "diarizing"
            job.diarize_progress = 0.0

    def set_diarize_progress(self, job_id: str, completed: int, total: int) -> None:
        with self._lock:
            self._jobs[job_id].diarize_progress = completed / total

    def set_done(self, job_id: str, segments: list[dict]) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = "done"
            job.result = {"segments": segments}
            job.finished_at = time.time()

    def set_failed(self, job_id: str, error: str, retryable: bool = True) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = "failed"
            job.error = error
            job.retryable = retryable
            job.finished_at = time.time()


job_store = JobStore()
