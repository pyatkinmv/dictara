import 'tag.dart';

enum TranscriptionStatus { done, processing, pending, failed }

/// What a `processing` transcription is busy with.
enum ProcessingPhase { transcribing, diarizing, summarizing }

/// One item of the transcriptions list (see `docs/design.md`, "Job").
class Transcription {
  const Transcription({
    required this.id,
    required this.title,
    this.duration,
    required this.uploadedAt,
    required this.status,
    this.processingPct,
    this.processingPhase,
    this.tags = const [],
    this.speakerCount = 0,
  });

  final String id;
  final String title;

  /// Null when the source does not know it (the gateway sends it once the audio has been transcribed).
  final Duration? duration;
  final DateTime uploadedAt;
  final TranscriptionStatus status;

  /// Percent of the audio transcribed so far. Only while [status] is [TranscriptionStatus.processing]
  /// and [processingPhase] is [ProcessingPhase.transcribing]; the later phases have no percentage.
  final int? processingPct;

  /// Only while [status] is [TranscriptionStatus.processing]; null if the source does not say.
  final ProcessingPhase? processingPhase;
  final List<Tag> tags;
  final int speakerCount;

  Transcription copyWith({String? title, List<Tag>? tags}) {
    return Transcription(
      id: id,
      title: title ?? this.title,
      duration: duration,
      uploadedAt: uploadedAt,
      status: status,
      processingPct: processingPct,
      processingPhase: processingPhase,
      tags: tags ?? this.tags,
      speakerCount: speakerCount,
    );
  }
}

/// One page of the transcriptions list: [items] in list order and whether
/// another page follows them.
class TranscriptionPage {
  const TranscriptionPage(this.items, {required this.hasNext});

  final List<Transcription> items;
  final bool hasNext;
}
