"""faster-whisper decodes audio with `av.open(path, mode="r", metadata_errors="ignore")` (faster_whisper/audio.py). PyAV 19
removed `metadata_errors`, so after the image was rebuilt with the newest av on 2026-10-18 every transcription failed with
"open() got an unexpected keyword argument 'metadata_errors'". This makes the same call with the av that
requirements.txt allows, so an av that drops the argument fails here and not in production."""
import math
import struct
import wave

import pytest

av = pytest.importorskip("av")


def test_av_still_accepts_the_arguments_faster_whisper_passes(tmp_path):
    path = tmp_path / "tone.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"".join(struct.pack("<h", int(8000 * math.sin(i / 20))) for i in range(1600)))

    with av.open(str(path), mode="r", metadata_errors="ignore") as container:
        assert len(container.streams.audio) == 1
