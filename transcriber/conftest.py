import sys
from unittest.mock import MagicMock

# Mock heavy ML dependencies so tests can import app without loading models
for mod in [
    "faster_whisper",
    "pyannote", "pyannote.audio", "pyannote.audio.pipelines",
    "torch", "torchaudio",
]:
    sys.modules.setdefault(mod, MagicMock())

# ctranslate2 needs get_cuda_device_count() to return an integer so that the
# comparison `ctranslate2.get_cuda_device_count() > 0` in Transcriber.__init__
# doesn't raise TypeError (MagicMock() > 0 is not supported on Python 3.12).
_ctranslate2_mock = MagicMock()
_ctranslate2_mock.get_cuda_device_count.return_value = 0
sys.modules.setdefault("ctranslate2", _ctranslate2_mock)
