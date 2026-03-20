enum JobStatus { pending, processing, summarizing, done, failed }

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
  final List<String> tags;
  final int? queuePosition;

  const JobResult({
    required this.status,
    this.segments,
    this.formattedText,
    this.summary,
    this.audioDurationS,
    this.error,
    this.progress,
    this.durationS,
    this.tags = const [],
    this.queuePosition,
  });

  JobResult copyWith({List<String>? tags}) => JobResult(
    status: status,
    segments: segments,
    formattedText: formattedText,
    summary: summary,
    audioDurationS: audioDurationS,
    error: error,
    progress: progress,
    durationS: durationS,
    tags: tags ?? this.tags,
    queuePosition: queuePosition,
  );

  factory JobResult.fromJson(Map<String, dynamic> json) {
    final statusStr = json['status'] as String;
    final status = JobStatus.values.firstWhere(
      (s) => s.name == statusStr,
      orElse: () => JobStatus.failed,
    );

    final resultMap = json['result'] as Map<String, dynamic>?;
    List<TranscriptSegment>? segments;
    if (resultMap != null) {
      final segs = resultMap['segments'] as List<dynamic>?;
      segments = segs?.map((s) => TranscriptSegment.fromJson(s as Map<String, dynamic>)).toList();
    }

    ProgressInfo? progress;
    final prog = json['progress'] as Map<String, dynamic>?;
    if (prog != null) progress = ProgressInfo.fromJson(prog);

    return JobResult(
      status: status,
      segments: segments,
      formattedText: resultMap?['formatted_text'] as String?,
      summary: resultMap?['summary'] as String?,
      audioDurationS: (resultMap?['audio_duration_s'] as num?)?.toDouble(),
      error: json['error'] as String?,
      progress: progress,
      durationS: (json['duration_s'] as num?)?.toDouble(),
      tags: (json['tags'] as List<dynamic>?)?.cast<String>() ?? [],
      queuePosition: json['queue_position'] as int?,
    );
  }

  String toTranscriptText() => formattedText ?? '';
}

class HistoryItem {
  final String jobId;
  final String fileName;
  final DateTime createdAt;
  JobStatus status;
  List<String> tags;

  HistoryItem({
    required this.jobId,
    required this.fileName,
    required this.createdAt,
    required this.status,
    this.tags = const [],
  });

  factory HistoryItem.fromJson(Map<String, dynamic> json) => HistoryItem(
        jobId: json['job_id'] as String,
        fileName: json['file_name'] as String,
        createdAt: DateTime.parse(json['created_at'] as String),
        status: JobStatus.values.firstWhere(
          (s) => s.name == json['status'] as String,
          orElse: () => JobStatus.failed,
        ),
        tags: (json['tags'] as List<dynamic>?)?.cast<String>() ?? [],
      );
}
