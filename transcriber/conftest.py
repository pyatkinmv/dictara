import sys
from unittest.mock import MagicMock

# Mock heavy ML dependencies so tests can import app without loading models
for mod in [
    "faster_whisper",
    "pyannote", "pyannote.audio", "pyannote.audio.pipelines",
    "torch", "torchaudio",
    "ctranslate2",
]:
    sys.modules.setdefault(mod, MagicMock())
