import time
from jobs import JobStore


def test_set_done_populates_result_fields():
    store = JobStore()
    job_id = store.create(tmp_path="/tmp/fake.wav")
    store.set_processing(job_id)

    segments = [{"start": 0.0, "end": 2.4, "text": "Hello"}]
    store.set_done(job_id, segments, language="ru", audio_duration_s=42.3)

    job = store.get(job_id)
    assert job.status == "done"
    assert job.result["segments"] == segments
    assert job.result["language"] == "ru"
    assert job.result["audio_duration_s"] == 42.3
    assert job.finished_at is not None


def test_set_done_accepts_none_for_optional_fields():
    store = JobStore()
    job_id = store.create(tmp_path="/tmp/fake2.wav")
    store.set_processing(job_id)

    store.set_done(job_id, segments=[], language=None, audio_duration_s=None)

    job = store.get(job_id)
    assert job.status == "done"
    assert job.result["language"] is None
    assert job.result["audio_duration_s"] is None


def test_set_done_with_speaker_segments():
    store = JobStore()
    job_id = store.create(tmp_path="/tmp/fake3.wav")
    store.set_processing(job_id)

    segments = [
        {"start": 0.0, "end": 1.5, "text": "Hi", "speaker": "SPEAKER_00"},
        {"start": 1.5, "end": 3.0, "text": "Hello", "speaker": "SPEAKER_01"},
    ]
    store.set_done(job_id, segments, language="en", audio_duration_s=3.0)

    job = store.get(job_id)
    assert len(job.result["segments"]) == 2
    assert job.result["segments"][0]["speaker"] == "SPEAKER_00"
    assert job.result["language"] == "en"
