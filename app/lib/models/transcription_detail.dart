import 'speaker.dart';
import 'transcription.dart';

/// One row of the transcript.
class Segment {
  const Segment({required this.start, required this.speakerSlot, required this.text});

  final Duration start;

  /// 1-based slot number assigned by the diarizer.
  final int speakerSlot;
  final String text;
}

/// Consecutive segments by the same speaker, rendered as one block.
class SpeakerRun {
  const SpeakerRun({required this.speaker, required this.segments});

  final Speaker speaker;
  final List<Segment> segments;
}

/// The AI summary shown on the Overview tab.
///
/// Today the gateway stores one plain-text blob; the three structured parts
/// need `docs/api-gaps.md` §1.1 (deferred).
class Summary {
  const Summary({required this.text, this.keyPoints = const [], this.actionItems = const []});

  final String text;
  final List<String> keyPoints;
  final List<String> actionItems;
}

/// A finished transcription opened on its own page.
class TranscriptionDetail {
  const TranscriptionDetail({
    required this.item,
    required this.segments,
    required this.originalFilename,
    required this.fileSizeBytes,
    this.speakers = const {},
    this.summary,
    this.language,
    this.wordCount,
  });

  /// The list-level data (title, tags, status, duration, date...).
  final Transcription item;
  final List<Segment> segments;

  /// Confirmed mapping of diarizer slots to named speakers.
  final Map<int, Speaker> speakers;

  /// Null until the summary has been generated (or when summarizing is off).
  final Summary? summary;

  /// Name of the uploaded file; the title can be renamed, this cannot.
  final String originalFilename;
  final int fileSizeBytes;

  /// Detected language code, e.g. `EN`.
  final String? language;

  /// Approximate; not stored by the gateway yet (`docs/api-gaps.md` §1.4).
  final int? wordCount;

  Speaker speakerFor(int slot) => speakers[slot] ?? Speaker.unassigned(slot);

  /// Groups consecutive segments by effective speaker (compared by name, as
  /// in the design: two slots mapped to the same person merge into one block).
  List<SpeakerRun> get runs {
    final runs = <SpeakerRun>[];
    for (final segment in segments) {
      final speaker = speakerFor(segment.speakerSlot);
      if (runs.isNotEmpty && runs.last.speaker.name == speaker.name) {
        runs.last.segments.add(segment);
      } else {
        runs.add(SpeakerRun(speaker: speaker, segments: [segment]));
      }
    }
    return runs;
  }

  TranscriptionDetail copyWith({Transcription? item}) {
    return TranscriptionDetail(
      item: item ?? this.item,
      segments: segments,
      speakers: speakers,
      summary: summary,
      originalFilename: originalFilename,
      fileSizeBytes: fileSizeBytes,
      language: language,
      wordCount: wordCount,
    );
  }
}
