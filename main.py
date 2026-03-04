#!/usr/bin/env python3
"""
Dictara - Local audio/video transcription CLI
Usage: python main.py <audio_file> [options]
"""

import argparse
import sys
from pathlib import Path

from transcriber import Transcriber, SUPPORTED_EXTENSIONS, segments_to_text


def parse_args():
    parser = argparse.ArgumentParser(
        description="Transcribe audio/video files to text using Whisper.",
        formatter_class=argparse.RawTextHelpFormatter,
    )
    parser.add_argument("input", help="Path to audio/video file")
    parser.add_argument(
        "-o", "--output",
        help="Output .txt file path (default: same name as input with .txt extension)",
    )
    parser.add_argument(
        "-m", "--model",
        default="small",
        choices=["tiny", "base", "small", "medium", "large-v2", "large-v3"],
        help="Whisper model size (default: small)\n"
             "  tiny    ~75MB  fastest, lower accuracy\n"
             "  base    ~145MB fast, decent accuracy\n"
             "  small   ~466MB good balance (default)\n"
             "  medium  ~1.5GB great accuracy, slow on CPU\n"
             "  large-v3 ~3GB  best accuracy, needs GPU",
    )
    parser.add_argument(
        "-l", "--language",
        default=None,
        help="Language code hint, e.g. 'en', 'de', 'fr' (default: auto-detect)",
    )
    parser.add_argument(
        "--no-timestamps",
        action="store_true",
        help="Output plain text without timestamps",
    )
    parser.add_argument(
        "--device",
        default="auto",
        choices=["auto", "cpu", "cuda"],
        help="Device to run inference on (default: auto)",
    )
    return parser.parse_args()


def resolve_output_path(input_path: Path, output_arg: str | None) -> Path:
    if output_arg:
        return Path(output_arg)
    return input_path.with_suffix(".txt")


def main():
    args = parse_args()
    input_path = Path(args.input)

    if not input_path.exists():
        print(f"Error: file not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    if input_path.suffix.lower() not in SUPPORTED_EXTENSIONS:
        print(
            f"Warning: '{input_path.suffix}' is not a recognized format. "
            f"Supported: {', '.join(sorted(SUPPORTED_EXTENSIONS))}",
            file=sys.stderr,
        )

    output_path = resolve_output_path(input_path, args.output)

    transcriber = Transcriber(model_size=args.model, device=args.device)

    print(f"Transcribing: {input_path}")
    segments = transcriber.transcribe(str(input_path), language=args.language)

    if not segments:
        print("No speech detected.", file=sys.stderr)
        sys.exit(1)

    text = segments_to_text(segments, timestamps=not args.no_timestamps)
    output_path.write_text(text, encoding="utf-8")

    print(f"Done. Output written to: {output_path}")
    print(f"Segments: {len(segments)}")


if __name__ == "__main__":
    main()
