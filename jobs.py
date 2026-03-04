import threading
import uuid
from dataclasses import dataclass
from typing import Literal

JobStatus = Literal["pending", "processing", "done", "failed"]


@dataclass
class Job:
    job_id: str
    status: JobStatus = "pending"
    result: dict | None = None   # {"segments": [...]} when done
    error: str | None = None
    tmp_path: str | None = None  # path to uploaded temp file


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
            self._jobs[job_id].status = "processing"

    def set_done(self, job_id: str, segments: list[dict]) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = "done"
            job.result = {"segments": segments}

    def set_failed(self, job_id: str, error: str) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = "failed"
            job.error = error


job_store = JobStore()
