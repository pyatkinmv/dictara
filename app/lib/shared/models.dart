import 'package:dictara_api/api.dart' as gen;

enum JobStatus { pending, processing, summarizing, done, failed }

class ProgressInfo {
  final double? processedS;
  final double? totalS;
  final String? phase;
  final double? diarizeProgress;

  const ProgressInfo({this.processedS, this.totalS, this.phase, this.diarizeProgress});
}

class TranscriptSegment {
  final double start;
  final double end;
  final String text;
  final String? speaker;

  const TranscriptSegment({
    required this.start,
    required this.end,
    required this.text,
    this.speaker,
  });
}

class JobResult {
  final JobStatus status;
  final List<TranscriptSegment>? segments;
  final String? formattedText;
  final String? summary;
  final double? audioDurationS;
  final String? error;
  final ProgressInfo? progress;
  final double? durationS;

  const JobResult({
    required this.status,
    this.segments,
    this.formattedText,
    this.summary,
    this.audioDurationS,
    this.error,
    this.progress,
    this.durationS,
  });

  /// Deserializes from the gateway JSON response via generated types.
  /// If the gateway renames a field, this will fail to compile — not at runtime.
  factory JobResult.fromJson(Map<String, dynamic> json) {
    final r = gen.JobResponse.fromJson(json)!;

    final status = switch (r.status) {
      'pending' => JobStatus.pending,
      'processing' => JobStatus.processing,
      'summarizing' => JobStatus.summarizing,
      'done' => JobStatus.done,
      _ => JobStatus.failed,
    };

    final progress = r.progress == null
        ? null
        : ProgressInfo(
            phase: r.progress!.phase,
            processedS: r.progress!.processedS,
            totalS: r.progress!.totalS,
            diarizeProgress: r.progress!.diarizeProgress,
          );

    final segments = r.result?.segments
        .map((s) => TranscriptSegment(
              start: s.start,
              end: s.end,
              text: s.text,
              speaker: s.speaker,
            ))
        .toList();

    return JobResult(
      status: status,
      segments: segments,
      formattedText: r.result?.formattedText,
      summary: r.result?.summary,
      audioDurationS: r.result?.audioDurationS,
      error: r.error,
      progress: progress,
      durationS: r.durationS,
    );
  }

  String toTranscriptText() => formattedText ?? '';
}
