from unittest.mock import AsyncMock, MagicMock, patch

from fastapi.testclient import TestClient

import app as transcriber_app

app = transcriber_app.app


def _load_model():
    app.state.transcribers = {"small": MagicMock()}
    app.state.diarizer = None


def test_transcribe_requires_file_or_storage_uri():
    _load_model()
    resp = TestClient(app).post("/transcribe", params={"model": "small"})
    assert resp.status_code == 400
    assert "Provide either" in resp.json()["detail"]


def test_transcribe_rejects_both_file_and_storage_uri():
    _load_model()
    resp = TestClient(app).post(
        "/transcribe",
        params={"model": "small", "storage_uri": "gs://bucket/audio/abc/file.m4a"},
        files={"file": ("audio.m4a", b"fake-bytes", "audio/mp4")},
    )
    assert resp.status_code == 400
    assert "only one" in resp.json()["detail"]


def test_transcribe_downloads_from_storage_uri():
    _load_model()
    with patch.object(transcriber_app, "_download_from_gcs", return_value="/tmp/fake.m4a") as mock_download, \
            patch.object(transcriber_app, "_dispatch", new=AsyncMock()):
        resp = TestClient(app).post(
            "/transcribe", params={"model": "small", "storage_uri": "gs://bucket/audio/abc/file.m4a"}
        )

    assert resp.status_code == 202
    assert "job_id" in resp.json()
    mock_download.assert_called_once_with("gs://bucket/audio/abc/file.m4a")


def test_download_from_gcs_fetches_blob_to_tempfile():
    blob = MagicMock()
    client = MagicMock()
    client.bucket.return_value.blob.return_value = blob
    transcriber_app._gcs_client = None

    # Avoid touching the real filesystem — NamedTemporaryFile(dir="/tmp", ...) only
    # exists on the container's Linux filesystem, not on every dev machine.
    fake_tmp = MagicMock()
    fake_tmp.name = "/tmp/tmpabc123.m4a"
    fake_tmp.__enter__.return_value = fake_tmp
    fake_tmp.__exit__.return_value = False

    with patch.object(transcriber_app.storage, "Client", return_value=client), \
            patch.object(transcriber_app.tempfile, "NamedTemporaryFile", return_value=fake_tmp) as mock_tmp:
        tmp_path = transcriber_app._download_from_gcs("gs://my-bucket/audio/abc/recording.m4a")

    client.bucket.assert_called_once_with("my-bucket")
    client.bucket.return_value.blob.assert_called_once_with("audio/abc/recording.m4a")
    mock_tmp.assert_called_once_with(dir="/tmp", suffix=".m4a", delete=False)
    blob.download_to_filename.assert_called_once_with("/tmp/tmpabc123.m4a")
    assert tmp_path == "/tmp/tmpabc123.m4a"
