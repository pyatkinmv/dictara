"""
Test suite for job cancellation in the transcriber service.

## How subprocess cancellation works

When a job is submitted via POST /transcribe, the actual transcription work is delegated
to a child process spawned via multiprocessing.Process (using Linux fork). The parent
thread (running in ThreadPoolExecutor) monitors the child every 0.1s using a shared Queue.

  Child process (_transcription_worker):
    - Inherits app.state.transcribers and app.state.diarizer via fork (no model reload)
    - Sends typed dicts over the queue: "processing", "progress", "diarizing",
      "diarize_progress", "done", "failed"
    - Cleans up temp files in its finally block

  Parent thread (_run_transcription_subprocess):
    - Drains the queue and applies messages to job_store
    - Checks job.cancel_requested on each iteration (every 0.1s)
    - On cancel: sends SIGTERM, waits 3s, SIGKILL if still alive, sets status=cancelled

  Cancel endpoint (POST /jobs/{id}/cancel):
    - Sets job.cancel_requested = True
    - Returns {"cancelled": true} if the job was pending or processing
    - Returns {"cancelled": false} for terminal states (done, failed, cancelled)

## How tests mock the subprocess pipeline

Tests use FastAPI's TestClient (synchronous, runs the ASGI event loop internally).
conftest.py already mocks faster_whisper, pyannote, torch, and ctranslate2 so the
Transcriber and Diarizer classes are importable and instantiable without real models.

Each test uses two patches stacked:

  1. subprocess.run — mocked to return CompletedProcess([], 0) so the ffmpeg
     WAV-conversion step succeeds without needing real audio or a real ffmpeg binary.
     This mock is active in the parent process before fork, so the child inherits it.

  2. app.state.transcribers["small"].transcribe — the Transcriber instance's transcribe()
     method is patched to return fake segments immediately (fast tests) or to call
     time.sleep(30) (slow tests that need cancellation).
     Because mock.patch.object modifies the instance in-place and fork copies the
     parent's memory, the child process runs the patched version.

## Important: fork inherits patches

Python's mock.patch.object replaces an attribute on a live object in memory. When
multiprocessing uses fork, the child gets an exact copy of the parent's address space,
including those patched attributes. This means mocks set up in the parent are
automatically active in the child — no special inter-process setup needed.
"""

import multiprocessing
import subprocess as _subprocess
import sys
import time
import unittest.mock
from fastapi.testclient import TestClient
import pytest

from app import app
from jobs import job_store

# Tests that spawn child processes require fork semantics so that unittest.mock
# patches (set up in the parent) are inherited by the child without re-importing.
# fork is available on Linux (production Docker target) but not on Windows.
_requires_fork = pytest.mark.skipif(
    "fork" not in multiprocessing.get_all_start_methods(),
    reason="subprocess tests require fork (Linux only); spawn re-imports modules, breaking mock inheritance",
)


# ── fixtures ───────────────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def client():
    """
    Single TestClient shared across all tests in this module.

    scope="module" means the FastAPI lifespan (model loading) runs exactly once,
    which is faster and matches production behaviour where models stay loaded between
    requests. app.state.transcribers["small"] is the same Transcriber instance
    throughout, so patch.object calls in individual tests all target the same object.
    """
    with TestClient(app) as c:
        yield c


# ── helpers ────────────────────────────────────────────────────────────────────

def wait_for_status(client: TestClient, job_id: str, target: str, timeout: float = 10.0) -> None:
    """Poll GET /jobs/{job_id} until status equals target or the deadline is reached."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        resp = client.get(f"/jobs/{job_id}")
        assert resp.status_code == 200, f"GET /jobs/{job_id} returned {resp.status_code}"
        if resp.json()["status"] == target:
            return
        time.sleep(0.1)
    last = client.get(f"/jobs/{job_id}").json()["status"]
    raise TimeoutError(f"Job {job_id}: expected '{target}', got '{last}' after {timeout}s")


def submit_job(client: TestClient, model: str = "small", diarize: bool = False) -> str:
    """Submit a fake audio file and return the job_id."""
    resp = client.post(
        f"/transcribe?model={model}&diarize={diarize}",
        files={"file": ("audio.m4a", b"\x00" * 16, "audio/mp4")},
    )
    assert resp.status_code == 202, f"Submit failed: {resp.text}"
    return resp.json()["job_id"]


# ── mock helpers ───────────────────────────────────────────────────────────────

def _mock_ffmpeg():
    """
    Return a context manager that patches subprocess.run to succeed silently.

    The patch is applied before fork so the child process inherits it. Without this,
    the child would call real ffmpeg on the 16-byte fake audio and fail with a
    "Could not decode the file" error before our transcribe() mock is ever reached.
    """
    return unittest.mock.patch(
        "subprocess.run",
        return_value=_subprocess.CompletedProcess([], 0),
    )


def _mock_transcribe_fast(segments=None):
    """
    Patch Transcriber.transcribe() to return fake segments immediately.

    Used by tests that verify normal (non-cancelled) completion through the subprocess.
    """
    if segments is None:
        segments = [{"start": 0.0, "end": 1.0, "text": "Hello world"}]
    return unittest.mock.patch.object(
        app.state.transcribers["small"], "transcribe", return_value=segments
    )


def _mock_transcribe_slow():
    """
    Patch Transcriber.transcribe() to sleep for 30 seconds.

    Used by cancellation tests. The child inherits this patch via fork and blocks in
    time.sleep(30). The parent's cancel loop sends SIGTERM, which kills the sleeping
    child immediately.
    """
    def _slow(*args, **kwargs):
        time.sleep(30)
        return []

    return unittest.mock.patch.object(
        app.state.transcribers["small"], "transcribe", side_effect=_slow
    )


# ── tests ──────────────────────────────────────────────────────────────────────

def test_cancel_nonexistent_job(client):
    """Cancelling an unknown job_id must return 404."""
    resp = client.post("/jobs/does-not-exist/cancel")
    assert resp.status_code == 404


def test_cancel_pending_job_sets_flag(client):
    """
    request_cancel on a pending job (before the subprocess starts) sets cancel_requested=True
    and returns {"cancelled": true}.

    This verifies the cancel endpoint handles the "pending" state correctly. When the
    executor eventually picks up the job, the monitoring loop will check the flag on
    its first iteration and kill the child before any real work is done.

    We test this directly via the job_store (without going through the HTTP layer for
    the second part) to avoid a race with the executor picking up the job.
    """
    # Create a pending job directly in the store (never dispatched)
    job_id = job_store.create("/tmp/fake.m4a")

    resp = client.post(f"/jobs/{job_id}/cancel")
    assert resp.status_code == 200
    assert resp.json() == {"cancelled": True}

    job = job_store.get(job_id)
    assert job.cancel_requested is True
    assert job.status == "pending"  # still pending — subprocess not started yet


@_requires_fork
def test_cancel_already_done_job(client):
    """
    Cancelling a completed job must return {"cancelled": false}.

    The job reaches "done" naturally via the fast mock. Calling cancel afterwards
    is a safe no-op: the job stays "done" and cancelled=false is returned.
    """
    fake_segments = [
        {"start": 0.0, "end": 1.0, "text": "Hello"},
        {"start": 1.0, "end": 2.0, "text": "World"},
    ]
    with _mock_ffmpeg(), _mock_transcribe_fast(fake_segments):
        job_id = submit_job(client)
        wait_for_status(client, job_id, "done")

    cancel_resp = client.post(f"/jobs/{job_id}/cancel")
    assert cancel_resp.status_code == 200
    assert cancel_resp.json() == {"cancelled": False}
    assert client.get(f"/jobs/{job_id}").json()["status"] == "done"


@_requires_fork
def test_cancel_already_failed_job(client):
    """
    Cancelling a failed job must return {"cancelled": false}.

    The worker is patched to raise RuntimeError, which causes the child to send a
    "failed" message. Calling cancel on a terminal state must be a safe no-op.
    """
    with _mock_ffmpeg():
        with unittest.mock.patch.object(
            app.state.transcribers["small"], "transcribe",
            side_effect=RuntimeError("simulated error"),
        ):
            job_id = submit_job(client)
            wait_for_status(client, job_id, "failed")

    cancel_resp = client.post(f"/jobs/{job_id}/cancel")
    assert cancel_resp.status_code == 200
    assert cancel_resp.json() == {"cancelled": False}


@_requires_fork
def test_cancel_processing_job(client):
    """
    The core cancellation scenario:
      1. Submit a job with a slow mock (blocks for 30s in the child)
      2. Wait until the job reaches "processing" (subprocess has called set_processing)
      3. Call POST /jobs/{id}/cancel → {"cancelled": true}
      4. Verify the job reaches "cancelled" within the timeout

    Internally:
      - cancel endpoint sets job.cancel_requested = True
      - Monitor loop (polling every 0.1s) sees the flag
      - Calls process.terminate() → child receives SIGTERM and exits from time.sleep()
      - Monitor calls set_cancelled(job_id) and returns
      - GET /jobs/{id} returns {"status": "cancelled"}
    """
    with _mock_ffmpeg(), _mock_transcribe_slow():
        job_id = submit_job(client)
        wait_for_status(client, job_id, "processing")

        cancel_resp = client.post(f"/jobs/{job_id}/cancel")
        assert cancel_resp.status_code == 200
        assert cancel_resp.json() == {"cancelled": True}

        wait_for_status(client, job_id, "cancelled", timeout=10)

    result = client.get(f"/jobs/{job_id}").json()
    assert result["status"] == "cancelled"


@_requires_fork
def test_cancel_twice(client):
    """
    Calling cancel twice is idempotent:
      - First call: {"cancelled": true}  (job is processing)
      - Second call: {"cancelled": false} (job is already cancelled — terminal state)

    This verifies that request_cancel returns False for the "cancelled" status, and that
    the second call does not attempt to kill a non-existent process.
    """
    with _mock_ffmpeg(), _mock_transcribe_slow():
        job_id = submit_job(client)
        wait_for_status(client, job_id, "processing")

        first = client.post(f"/jobs/{job_id}/cancel")
        assert first.json() == {"cancelled": True}

        wait_for_status(client, job_id, "cancelled", timeout=10)

    second = client.post(f"/jobs/{job_id}/cancel")
    assert second.status_code == 200
    assert second.json() == {"cancelled": False}


@_requires_fork
def test_normal_job_completes_via_subprocess(client):
    """
    Happy path: a job with a fast mock completes successfully through the subprocess.

    This is the regression test for the subprocess refactor — verifies that normal
    operation (no cancellation) still produces correct results: segments flow through
    the multiprocessing.Queue, job_store is updated to "done", and the result contains
    the expected segments.
    """
    fake_segments = [
        {"start": 0.0, "end": 1.5, "text": "Hello world"},
        {"start": 1.5, "end": 3.0, "text": "Goodbye world"},
    ]
    with _mock_ffmpeg(), _mock_transcribe_fast(fake_segments):
        job_id = submit_job(client)
        wait_for_status(client, job_id, "done")

    result = client.get(f"/jobs/{job_id}").json()
    assert result["status"] == "done"
    assert result["result"] is not None
    segments = result["result"]["segments"]
    assert len(segments) == 2
    assert segments[0]["text"] == "Hello world"
    assert segments[1]["text"] == "Goodbye world"


@_requires_fork
def test_subprocess_failure_propagates(client):
    """
    When the worker raises an uncaught exception, the job transitions to "failed" and
    the parent thread exits cleanly (executor stays functional for the next job).

    Flow:
      - Worker's transcribe() raises RuntimeError("disk full")
      - Worker catches it in the top-level except, sends {"type": "failed", ...}
      - Parent drains the message and calls job_store.set_failed()
      - Parent's monitor loop sees the process has exited, drains queue, joins
      - A subsequent job submitted to the same executor completes successfully
    """
    with _mock_ffmpeg():
        with unittest.mock.patch.object(
            app.state.transcribers["small"], "transcribe",
            side_effect=RuntimeError("disk full"),
        ):
            job_id = submit_job(client)
            wait_for_status(client, job_id, "failed")

    result = client.get(f"/jobs/{job_id}").json()
    assert result["status"] == "failed"
    assert "disk full" in (result["error"] or "")

    # Confirm the executor is still healthy (parent thread didn't crash or hang)
    fast_segments = [{"start": 0.0, "end": 1.0, "text": "still works"}]
    with _mock_ffmpeg(), _mock_transcribe_fast(fast_segments):
        job_id2 = submit_job(client)
        wait_for_status(client, job_id2, "done")

    assert client.get(f"/jobs/{job_id2}").json()["status"] == "done"
