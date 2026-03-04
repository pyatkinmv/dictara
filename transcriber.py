from faster_whisper import WhisperModel


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

    def transcribe(self, audio_path: str, language: str | None = None) -> list[dict]:
        """
        Transcribe an audio/video file.

        Returns a list of segments:
            [{"start": 0.0, "end": 2.4, "text": "Hello world"}, ...]
        """
        segments, info = self.model.transcribe(
            audio_path,
            language=language,
            beam_size=5,
        )

        detected = info.language if language is None else language
        print(f"Language: {detected} (confidence: {info.language_probability:.0%})")

        result = []
        for segment in segments:
            result.append({
                "start": segment.start,
                "end": segment.end,
                "text": segment.text.strip(),
            })
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
