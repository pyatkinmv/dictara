import 'speaker.dart';
import 'suggestions.dart';
import 'transcription.dart';

/// One row of the transcript.
class Segment {
  const Segment({required this.start, this.speakerSlot, required this.text});

  final Duration start;

  /// 1-based slot number assigned by the diarizer; null when the audio was not diarized.
  final int? speakerSlot;
  final String text;
}

/// Consecutive segments by the same speaker, rendered as one block.
class SpeakerRun {
  const SpeakerRun({required this.speaker, required this.firstIndex, required this.segments});

  /// Null for a transcript without diarization: there is nobody to name.
  final Speaker? speaker;

  /// Position of the first segment in the whole transcript.
  final int firstIndex;
  final List<Segment> segments;
}

/// Where "Regenerate summary" stands for a transcription. How many times it may be done is the user's plan.
enum SummaryRegeneration {
  /// The button is there.
  available,

  /// Gemini is being asked again; the old summary stays until the new one is ready.
  running,

  /// The plan's limit is reached: [TranscriptionDetail.regeneratedAt] says when it was last done.
  used,

  /// Nothing to ask (no summary service, or the plan has none), so no button.
  unavailable,
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
    this.suggestions,
    this.summary,
    this.language,
    this.wordCount,
    this.regeneration = SummaryRegeneration.unavailable,
    this.regeneratedAt,
    this.regenerationsUsed = 0,
    this.regenerationsMax,
    this.regenerationError,
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

  /// AI proposals still waiting for the user; null when there are none.
  final Suggestions? suggestions;

  /// The AI summary exactly as the summarizer wrote it: Markdown text. Null until it has been generated
  /// (or when summarizing is off).
  final String? summary;

  /// Name of the uploaded file; the title can be renamed, this cannot.
  final String originalFilename;
  final int fileSizeBytes;

  /// Detected language code, e.g. `EN`.
  final String? language;

  /// Counted by the gateway when the page is opened.
  final int? wordCount;

  /// The state of "Regenerate summary", when it was last done, how many times so far and how many the plan allows
  /// (null: as many as wanted).
  final SummaryRegeneration regeneration;
  final DateTime? regeneratedAt;
  final int regenerationsUsed;
  final int? regenerationsMax;

  /// Why the last attempt failed, in words for the user ("The summary service has run out of credits."); null when it did
  /// not fail. The gateway keeps it until the next attempt.
  final String? regenerationError;

  /// How many more times it can be done; null when there is no limit.
  int? get regenerationsLeft {
    final max = regenerationsMax;
    if (max == null) return null;
    final left = max - regenerationsUsed;
    return left < 0 ? 0 : left;
  }

  /// The speaker of a diarizer slot: the confirmed mapping, else "Speaker N" -
  /// tinted with the color of a pending AI suggestion for that slot, else grey.
  Speaker speakerFor(int slot) {
    final confirmed = speakers[slot];
    if (confirmed != null) return confirmed;
    final unassigned = Speaker.unassigned(slot);
    final suggested = suggestions?.speakers[slot];
    return suggested == null ? unassigned : Speaker(unassigned.name, suggested.color);
  }

  /// Who speaks segment [index]: a manual assignment, else its slot's speaker; null without diarization.
  Speaker? speakerOfSegment(int index) {
    final slot = segments[index].speakerSlot;
    return segmentSpeakers[index] ?? (slot == null ? null : speakerFor(slot));
  }

  /// Indexes of the segments currently attributed to the speaker called [name].
  Set<int> segmentsBySpeaker(String name) => {
        for (var i = 0; i < segments.length; i++)
          if (speakerOfSegment(i)?.name == name) i,
      };

  /// Every speaker actually heard in the transcript right now — one entry per distinct name, in the order
  /// each first appears — used by the "Speakers:" header row and the Overview speaker count, so both track
  /// exactly what the transcript body shows: a manual reassignment (one phrase or a whole voice) adds, merges
  /// or removes a name immediately, with no separate "confirm" step to keep it in sync.
  List<Speaker> get presentSpeakers {
    final seen = <String>{};
    final result = <Speaker>[];
    for (var i = 0; i < segments.length; i++) {
      final speaker = speakerOfSegment(i);
      if (speaker != null && seen.add(speaker.name)) result.add(speaker);
    }
    return result;
  }

  /// Groups consecutive segments by effective speaker (compared by name, as
  /// in the design: two slots mapped to the same person merge into one block).
  List<SpeakerRun> get runs {
    final runs = <SpeakerRun>[];
    for (var i = 0; i < segments.length; i++) {
      final speaker = speakerOfSegment(i);
      if (runs.isNotEmpty && runs.last.speaker?.name == speaker?.name) {
        runs.last.segments.add(segments[i]);
      } else {
        runs.add(SpeakerRun(speaker: speaker, firstIndex: i, segments: [segments[i]]));
      }
    }
    return runs;
  }

  TranscriptionDetail copyWith({
    Transcription? item,
    Map<int, Speaker>? segmentSpeakers,
    SummaryRegeneration? regeneration,
    bool clearRegenerationError = false,
  }) {
    return TranscriptionDetail(
      item: item ?? this.item,
      segments: segments,
      speakers: speakers,
      segmentSpeakers: segmentSpeakers ?? this.segmentSpeakers,
      suggestions: suggestions,
      summary: summary,
      originalFilename: originalFilename,
      fileSizeBytes: fileSizeBytes,
      language: language,
      wordCount: wordCount,
      regeneration: regeneration ?? this.regeneration,
      regeneratedAt: regeneratedAt,
      regenerationsUsed: regenerationsUsed,
      regenerationsMax: regenerationsMax,
      regenerationError: clearRegenerationError ? null : regenerationError,
    );
  }
}
