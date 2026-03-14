enum JobStatus { pending, processing, done, failed }

class ProgressInfo {
  final double? processedS;
  final double? totalS;
  final String? phase;
  final double? diarizeProgress;

  const ProgressInfo({this.processedS, this.totalS, this.phase, this.diarizeProgress});

  factory ProgressInfo.fromJson(Map<String, dynamic> json) => ProgressInfo(
        processedS: (json['processed_s'] as num?)?.toDouble(),
        totalS: (json['total_s'] as num?)?.toDouble(),
        phase: json['phase'] as String?,
        diarizeProgress: (json['diarize_progress'] as num?)?.toDouble(),
      );
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

  factory TranscriptSegment.fromJson(Map<String, dynamic> json) => TranscriptSegment(
        start: (json['start'] as num).toDouble(),
        end: (json['end'] as num).toDouble(),
        text: json['text'] as String,
        speaker: json['speaker'] as String?,
      );

  String toLine() {
    final mm = (start ~/ 60).toString().padLeft(2, '0');
    final ss = (start % 60).toStringAsFixed(0).padLeft(2, '0');
    final prefix = speaker != null ? '[$speaker] ' : '';
    return '$prefix[$mm:$ss] $text';
  }
}

class JobResult {
  final JobStatus status;
  final List<TranscriptSegment>? segments;
  final String? error;
  final ProgressInfo? progress;
  final double? durationS;

  const JobResult({
    required this.status,
    this.segments,
    this.error,
    this.progress,
    this.durationS,
  });

  factory JobResult.fromJson(Map<String, dynamic> json) {
    final statusStr = json['status'] as String;
    final status = JobStatus.values.firstWhere(
      (s) => s.name == statusStr,
      orElse: () => JobStatus.failed,
    );

    List<TranscriptSegment>? segments;
    final result = json['result'] as Map<String, dynamic>?;
    if (result != null) {
      final segs = result['segments'] as List<dynamic>?;
      segments = segs?.map((s) => TranscriptSegment.fromJson(s as Map<String, dynamic>)).toList();
    }

    ProgressInfo? progress;
    final prog = json['progress'] as Map<String, dynamic>?;
    if (prog != null) progress = ProgressInfo.fromJson(prog);

    return JobResult(
      status: status,
      segments: segments,
      error: json['error'] as String?,
      progress: progress,
      durationS: (json['duration_s'] as num?)?.toDouble(),
    );
  }

  String toTranscriptText() {
    if (segments == null || segments!.isEmpty) return '';
    return segments!.map((s) => s.toLine()).join('\n');
  }
}
