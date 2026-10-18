"""Tests for _probe_metadata: the container's own facts (codec, bit rate, tags like creation_time),
read from the original upload before the ffmpeg -> WAV conversion strips them."""
import json
import subprocess
from unittest.mock import patch

from app import _probe_metadata


def _ffprobe_output(fmt_extra=None, stream_extra=None, streams=None):
    fmt = {"format_name": "mov,mp4,m4a", "bit_rate": "128000", **(fmt_extra or {})}
    if streams is None:
        streams = [{"codec_type": "audio", "codec_name": "aac", "sample_rate": "44100", "channels": 2, **(stream_extra or {})}]
    return json.dumps({"format": fmt, "streams": streams})


def _run_with(stdout: str):
    return patch("app.subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout=stdout, stderr=""))


def test_reads_codec_bitrate_and_tags_from_the_original_file():
    output = _ffprobe_output(fmt_extra={"tags": {"creation_time": "2026-06-22T14:41:00.000000Z"}})
    with _run_with(output):
        metadata = _probe_metadata("/tmp/original.m4a")

    assert metadata == {
        "format_name": "mov,mp4,m4a", "bit_rate": 128000, "codec": "aac",
        "sample_rate": 44100, "channels": 2, "tags": {"creation_time": "2026-06-22T14:41:00.000000Z"},
    }


def test_picks_the_first_audio_stream_and_ignores_a_video_one():
    output = _ffprobe_output(streams=[
        {"codec_type": "video", "codec_name": "h264"},
        {"codec_type": "audio", "codec_name": "opus", "sample_rate": "48000", "channels": 1},
    ])
    with _run_with(output):
        metadata = _probe_metadata("/tmp/video.mp4")

    assert metadata["codec"] == "opus"
    assert metadata["sample_rate"] == 48000
    assert metadata["channels"] == 1


def test_none_when_ffprobe_cannot_read_the_file():
    with patch("app.subprocess.run", side_effect=subprocess.CalledProcessError(1, "ffprobe")):
        assert _probe_metadata("/tmp/corrupt.mp3") is None


def test_none_when_ffprobe_succeeds_but_finds_nothing_worth_keeping():
    output = json.dumps({"format": {}, "streams": []})
    with _run_with(output):
        assert _probe_metadata("/tmp/empty.wav") is None


def test_a_container_with_no_tags_still_reports_its_technical_facts():
    output = _ffprobe_output()  # no "tags" key on the format block at all
    with _run_with(output):
        metadata = _probe_metadata("/tmp/plain.wav")

    assert metadata["tags"] == {}
    assert metadata["codec"] == "aac"


def test_creation_time_kept_on_the_audio_stream_is_used_when_the_format_has_none():
    output = _ffprobe_output(stream_extra={"tags": {"creation_time": "2026-06-22T14:41:00.000000Z"}})
    with _run_with(output):
        metadata = _probe_metadata("/tmp/stream-tagged.m4a")

    assert metadata["tags"] == {"creation_time": "2026-06-22T14:41:00.000000Z"}


def test_the_format_creation_time_wins_over_the_stream_one():
    output = _ffprobe_output(
        fmt_extra={"tags": {"creation_time": "2026-06-22T14:41:00.000000Z", "title": "memo"}},
        stream_extra={"tags": {"creation_time": "2020-01-01T00:00:00.000000Z"}},
    )
    with _run_with(output):
        metadata = _probe_metadata("/tmp/both.m4a")

    assert metadata["tags"] == {"creation_time": "2026-06-22T14:41:00.000000Z", "title": "memo"}
