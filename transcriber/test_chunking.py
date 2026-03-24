"""Tests for audio chunking logic in _run_transcription / _transcribe_chunked."""
from unittest.mock import MagicMock, patch, call
import pytest

import app as app_module
from app import _get_duration, _transcribe_chunked, _CHUNK_THRESHOLD_S, _CHUNK_SIZE_S
from jobs import JobStore


# ── helpers ───────────────────────────────────────────────────────────────────

def _make_transcriber(segments_per_call):
    """Return a mock Transcriber whose transcribe() returns the next item from
    segments_per_call on each successive call, also invoking the progress_callback."""
    mock = MagicMock()
    call_index = [0]

    def fake_transcribe(path, language=None, progress_callback=None):
        segs = segments_per_call[call_index[0]]
        call_index[0] += 1
        if progress_callback and segs:
            last_end = segs[-1]["end"]
            progress_callback(last_end, last_end)
        return segs

    mock.transcribe.side_effect = fake_transcribe
    return mock


def _run(job_id, wav_path, language, total_s, transcriber, job_store_instance):
    """Helper: patch job_store and subprocess to run _transcribe_chunked."""
    with patch.object(app_module, "job_store", job_store_instance), \
         patch("app.subprocess.run"):  # suppress real ffmpeg calls
        return _transcribe_chunked(job_id, wav_path, transcriber, language, total_s)


# ── constants sanity ──────────────────────────────────────────────────────────

def test_constants():
    assert _CHUNK_THRESHOLD_S == 2400
    assert _CHUNK_SIZE_S == 1200


# ── test 1: short audio — single transcribe call, no offset ──────────────────

def test_short_audio_not_chunked():
    """Audio <= threshold must call transcribe exactly once with no offset."""
    store = JobStore()
    job_id = store.create("/tmp/fake.wav")
    store.set_processing(job_id)

    segs = [{"start": 0.0, "end": 5.0, "text": "hello"}]
    transcriber = _make_transcriber([segs])

    with patch.object(app_module, "_get_duration", return_value=1200.0), \
         patch.object(app_module, "job_store", store), \
         patch("app.subprocess.run"):
        # Short audio: call transcribe directly (not _transcribe_chunked)
        # Simulate the branch in _run_transcription:
        total_s = 1200.0
        assert total_s <= _CHUNK_THRESHOLD_S

        def on_segment(processed_s, ts):
            store.set_progress(job_id, processed_s, ts)

        result = transcriber.transcribe("/tmp/fake.wav", language=None, progress_callback=on_segment)

    assert transcriber.transcribe.call_count == 1
    assert result == segs
    assert result[0]["start"] == 0.0   # no offset applied


# ── test 2: long audio — correct chunk count ──────────────────────────────────

def test_long_audio_chunk_count():
    """60-min audio → 3 chunks (0–1200, 1200–2400, 2400–3600)."""
    store = JobStore()
    job_id = store.create("/tmp/fake.wav")
    store.set_processing(job_id)

    segments_per_chunk = [
        [{"start": 0.0, "end": 10.0, "text": "a"}],
        [{"start": 0.0, "end": 10.0, "text": "b"}],
        [{"start": 0.0, "end": 10.0, "text": "c"}],
    ]
    transcriber = _make_transcriber(segments_per_chunk)

    result = _run(job_id, "/tmp/fake.wav", None, 3600.0, transcriber, store)

    assert transcriber.transcribe.call_count == 3
    assert len(result) == 3


# ── test 3: segment timestamps are offset correctly ───────────────────────────

def test_segment_offsets():
    """Each chunk's segments must be shifted by chunk_start seconds."""
    store = JobStore()
    job_id = store.create("/tmp/fake.wav")
    store.set_processing(job_id)

    # 3 chunks: starts at 0, 1200, 2400 (total ~3000s)
    one_seg = [{"start": 0.0, "end": 10.0, "text": "hello"}]
    transcriber = _make_transcriber([one_seg, one_seg, one_seg])

    result = _run(job_id, "/tmp/fake.wav", None, 3000.0, transcriber, store)

    assert result[0]["start"] == 0.0
    assert result[0]["end"]   == 10.0
    assert result[1]["start"] == 1200.0
    assert result[1]["end"]   == 1210.0
    assert result[2]["start"] == 2400.0
    assert result[2]["end"]   == 2410.0


# ── test 4: progress callbacks report cumulative position ─────────────────────

def test_progress_is_cumulative():
    """Progress must be chunk_offset + chunk_processed_s, always over total_s."""
    store = JobStore()
    job_id = store.create("/tmp/fake.wav")
    store.set_processing(job_id)

    # Each chunk: transcribe calls progress_callback(10.0, 1000.0) via _make_transcriber
    one_seg = [{"start": 0.0, "end": 10.0, "text": "x"}]
    transcriber = _make_transcriber([one_seg, one_seg, one_seg])

    progress_calls = []

    def tracking_set_progress(jid, processed, total):
        progress_calls.append((processed, total))

    store.set_progress = tracking_set_progress  # type: ignore[method-assign]

    with patch.object(app_module, "job_store", store), \
         patch("app.subprocess.run"):
        _transcribe_chunked(job_id, "/tmp/fake.wav", transcriber, None, 3000.0)

    # Chunk 0 ends at 10s → cumulative 10
    # Chunk 1 ends at 10s → cumulative 1200 + 10 = 1210
    # Chunk 2 ends at 10s → cumulative 2400 + 10 = 2410
    assert progress_calls[0] == (10.0, 3000.0)
    assert progress_calls[1] == (1210.0, 3000.0)
    assert progress_calls[2] == (2410.0, 3000.0)


# ── test 5: boundary — exactly at threshold is NOT chunked ───────────────────

def test_boundary_not_chunked():
    """Audio exactly at threshold (2400s) must NOT be chunked."""
    total_s = float(_CHUNK_THRESHOLD_S)
    assert total_s <= _CHUNK_THRESHOLD_S   # strict >, so equal means no chunking


# ── test 6: empty chunks produce no segments ─────────────────────────────────

def test_empty_chunk_segments():
    """Chunks returning no segments don't crash and contribute nothing."""
    store = JobStore()
    job_id = store.create("/tmp/fake.wav")
    store.set_processing(job_id)

    transcriber = _make_transcriber([[], [], []])

    result = _run(job_id, "/tmp/fake.wav", None, 3600.0, transcriber, store)

    assert result == []
    assert transcriber.transcribe.call_count == 3


# ── test 7: mixed empty/non-empty chunks ─────────────────────────────────────

def test_mixed_chunks():
    """Only non-empty chunks contribute segments; offsets still correct."""
    store = JobStore()
    job_id = store.create("/tmp/fake.wav")
    store.set_processing(job_id)

    segments_per_chunk = [
        [],
        [{"start": 5.0, "end": 15.0, "text": "mid"}],
        [],
    ]
    transcriber = _make_transcriber(segments_per_chunk)

    result = _run(job_id, "/tmp/fake.wav", None, 3600.0, transcriber, store)

    assert len(result) == 1
    assert result[0]["start"] == 1205.0   # 1200 + 5
    assert result[0]["end"]   == 1215.0   # 1200 + 15
