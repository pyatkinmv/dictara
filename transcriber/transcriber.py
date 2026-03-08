import os

from faster_whisper import WhisperModel

# pyannote.audio 3.x passes use_auth_token= to hf_hub_download, which was
# removed in huggingface_hub 1.0. Patch it here before pyannote is imported.
def _patch_hf_hub_compat():
    try:
        import huggingface_hub
        _orig = huggingface_hub.hf_hub_download
        def _compat(*args, use_auth_token=None, **kwargs):
            if use_auth_token is not None:
                kwargs.setdefault("token", use_auth_token)
            return _orig(*args, **kwargs)
        huggingface_hub.hf_hub_download = _compat
        # Also patch on the submodule so pyannote's `from huggingface_hub import hf_hub_download` picks it up
        import huggingface_hub.file_download as _fd
        _fd.hf_hub_download = _compat
    except Exception:
        pass

_patch_hf_hub_compat()


SUPPORTED_EXTENSIONS = {".mp3", ".mp4", ".m4a", ".wav", ".ogg", ".flac", ".webm", ".mkv", ".avi", ".mov"}


class Transcriber:
    def __init__(self, model_size: str = "small", device: str = "auto"):
        """
        Args:
            model_size: One of tiny, base, small, medium, large-v3
            device: "auto" detects CUDA if available, otherwise CPU
        """
        if device == "auto":
            import ctranslate2
            device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"

        compute_type = "float16" if device == "cuda" else "int8"
        print(f"Loading model '{model_size}' on {device} ({compute_type})...")
        self.model = WhisperModel(model_size, device=device, compute_type=compute_type)
        print("Model ready.")

    def transcribe(self, audio_path: str, language: str | None = None, progress_callback=None) -> list[dict]:
        """
        Transcribe an audio/video file.

        Returns a list of segments:
            [{"start": 0.0, "end": 2.4, "text": "Hello world"}, ...]

        progress_callback(processed_s: float, total_s: float) is called after each segment.
        """
        segments, info = self.model.transcribe(
            audio_path,
            language=language,
            beam_size=10,
            vad_filter=True,
        )

        detected = info.language if language is None else language
        print(f"Language: {detected} (confidence: {info.language_probability:.0%}), duration: {info.duration:.1f}s")

        result = []
        for segment in segments:
            result.append({
                "start": segment.start,
                "end": segment.end,
                "text": segment.text.strip(),
            })
            if progress_callback:
                progress_callback(segment.end, info.duration)
        return result


class Diarizer:
    def __init__(self):
        from pyannote.audio import Pipeline
        import torch
        hf_token = os.environ.get("HF_TOKEN")
        if not hf_token:
            raise RuntimeError("HF_TOKEN env var is required for diarization")
        print("Loading diarization pipeline...")
        self.pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1",
            use_auth_token=hf_token,
        )
        device = "cuda" if _has_cuda() else "cpu"
        import torch
        self.pipeline.to(torch.device(device))
        print(f"Diarization pipeline ready (device={device}).")

    def diarize(self, audio_path: str, progress_callback=None):
        def hook(step_name, step_artifact, file=None, total=None, completed=None):
            if progress_callback and total and completed is not None:
                progress_callback(completed, total)
        return self.pipeline(audio_path, hook=hook)


def _has_cuda() -> bool:
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


def merge_diarization(segments: list[dict], diarization) -> list[dict]:
    """Assign each segment the speaker with the most overlap."""
    turns = list(diarization.itertracks(yield_label=True))  # (turn, _, speaker)
    result = []
    for seg in segments:
        best_speaker = None
        best_overlap = 0.0
        for turn, _, speaker in turns:
            overlap = min(seg["end"], turn.end) - max(seg["start"], turn.start)
            if overlap > best_overlap:
                best_overlap = overlap
                best_speaker = speaker
        out = dict(seg)
        if best_speaker is not None:
            out["speaker"] = best_speaker
        result.append(out)
    return result


def format_timestamp(seconds: float) -> str:
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    ms = int((seconds % 1) * 1000)
    return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"


def segments_to_text(segments: list[dict], timestamps: bool = True) -> str:
    lines = []
    for seg in segments:
        if timestamps:
            lines.append(f"[{format_timestamp(seg['start'])} --> {format_timestamp(seg['end'])}]  {seg['text']}")
        else:
            lines.append(seg["text"])
    return "\n".join(lines)
