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

/// A finished transcription opened on its own page.
class TranscriptionDetail {
  const TranscriptionDetail({
    required this.item,
    required this.segments,
    this.speakers = const {},
  });

  /// The list-level data (title, tags, status, duration, date...).
  final Transcription item;
  final List<Segment> segments;

  /// Confirmed mapping of diarizer slots to named speakers.
  final Map<int, Speaker> speakers;

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
    return TranscriptionDetail(item: item ?? this.item, segments: segments, speakers: speakers);
  }
}
