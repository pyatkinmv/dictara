import 'tag.dart';

enum TranscriptionStatus { done, processing, pending }

/// One item of the transcriptions list (see `docs/design.md`, "Job").
class Transcription {
  const Transcription({
    required this.id,
    required this.title,
    required this.duration,
    required this.uploadedAt,
    required this.status,
    this.processingPct,
    this.tags = const [],
    this.speakerCount = 0,
  });

  final int id;
  final String title;
  final Duration duration;
  final DateTime uploadedAt;
  final TranscriptionStatus status;

  /// Only while [status] is [TranscriptionStatus.processing].
  final int? processingPct;
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
      tags: tags ?? this.tags,
      speakerCount: speakerCount,
    );
  }
}
