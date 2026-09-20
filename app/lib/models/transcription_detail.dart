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
  const SpeakerRun({required this.speaker, required this.firstIndex, required this.segments});

  final Speaker speaker;

  /// Position of the first segment in the whole transcript.
  final int firstIndex;
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
    this.segmentSpeakers = const {},
    this.summary,
    this.language,
    this.wordCount,
  });

  /// The list-level data (title, tags, status, duration, date...).
  final Transcription item;
  final List<Segment> segments;

  /// Confirmed mapping of diarizer slots to named speakers.
  final Map<int, Speaker> speakers;

  /// Speakers the user assigned to single segments by hand, by segment index.
  /// They win over [speakers].
  ///
  /// How the gateway stores this is still open (`docs/api-gaps.md` §1.3).
  final Map<int, Speaker> segmentSpeakers;

  /// Null until the summary has been generated (or when summarizing is off).
  final Summary? summary;

  /// Name of the uploaded file; the title can be renamed, this cannot.
  final String originalFilename;
  final int fileSizeBytes;

  /// Detected language code, e.g. `EN`.
  final String? language;

  /// Approximate; not stored by the gateway yet (`docs/api-gaps.md` §1.4).
  final int? wordCount;

  /// The speaker of a diarizer slot (the confirmed mapping, else "Speaker N").
  Speaker speakerFor(int slot) => speakers[slot] ?? Speaker.unassigned(slot);

  /// Who speaks segment [index]: a manual assignment, else its slot's speaker.
  Speaker speakerOfSegment(int index) => segmentSpeakers[index] ?? speakerFor(segments[index].speakerSlot);

  /// Indexes of the segments currently attributed to the speaker called [name].
  Set<int> segmentsBySpeaker(String name) => {
        for (var i = 0; i < segments.length; i++)
          if (speakerOfSegment(i).name == name) i,
      };

  /// Groups consecutive segments by effective speaker (compared by name, as
  /// in the design: two slots mapped to the same person merge into one block).
  List<SpeakerRun> get runs {
    final runs = <SpeakerRun>[];
    for (var i = 0; i < segments.length; i++) {
      final speaker = speakerOfSegment(i);
      if (runs.isNotEmpty && runs.last.speaker.name == speaker.name) {
        runs.last.segments.add(segments[i]);
      } else {
        runs.add(SpeakerRun(speaker: speaker, firstIndex: i, segments: [segments[i]]));
      }
    }
    return runs;
  }

  TranscriptionDetail copyWith({Transcription? item, Map<int, Speaker>? segmentSpeakers}) {
    return TranscriptionDetail(
      item: item ?? this.item,
      segments: segments,
      speakers: speakers,
      segmentSpeakers: segmentSpeakers ?? this.segmentSpeakers,
      summary: summary,
      originalFilename: originalFilename,
      fileSizeBytes: fileSizeBytes,
      language: language,
      wordCount: wordCount,
    );
  }
}
