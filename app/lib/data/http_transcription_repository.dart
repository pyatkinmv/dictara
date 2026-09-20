import 'dart:ui' show Color;

import '../models/speaker.dart';
import '../models/speaker_entry.dart';
import '../models/tag.dart';
import '../models/tag_entry.dart';
import '../models/transcription.dart';
import '../models/transcription_detail.dart';
import '../models/upload.dart';
import 'gateway_client.dart';
import 'transcription_repository.dart';

/// A screen asked for something the gateway does not offer yet. Each such call names the integration
/// iteration (`docs/local-backend-integration.md`, section 7) that connects it.
class NotIntegratedException implements Exception {
  const NotIntegratedException(this.what, this.iteration);

  final String what;
  final int iteration;

  @override
  String toString() => '$what is not connected to the gateway yet (integration iteration $iteration)';
}

/// The real data source: the gateway over HTTP.
///
/// Being connected screen by screen (`docs/local-backend-integration.md`): so far only the transcription
/// list and the tag names work. The gateway pages, searches, filters and sorts the list itself. Everything
/// else throws [NotIntegratedException].
class HttpTranscriptionRepository implements TranscriptionRepository {
  HttpTranscriptionRepository(this._client);

  final GatewayClient _client;

  @override
  Future<TranscriptionPage> list(TranscriptionQuery query, {int page = 0, int size = transcriptionPageSize}) async {
    final body = await _client.getJson(_listPath(query, page, size));
    if (body is! Map<String, dynamic> || body['items'] is! List) {
      throw const GatewayException('Expected a page of transcriptions');
    }
    return TranscriptionPage(
      [for (final item in body['items'] as List<dynamic>) _transcriptionFromJson(item as Map<String, dynamic>)],
      hasNext: body['has_next'] == true,
    );
  }

  /// `/transcriptions?page=0&size=30&q=…&status=…&tag=…&sort=…`: only what narrows the list or changes its
  /// order is sent, and lists are sent as repeated parameters (a tag name may contain a comma).
  static String _listPath(TranscriptionQuery query, int page, int size) {
    final search = query.search.trim();
    final tags = query.tags.toList()..sort();
    return Uri(
      path: '/transcriptions',
      queryParameters: {
        'page': '$page',
        'size': '$size',
        if (search.isNotEmpty) 'q': search,
        // The enum names are the gateway's own: done, processing, pending, failed.
        if (query.statuses.isNotEmpty)
          'status': [for (final s in TranscriptionStatus.values) if (query.statuses.contains(s)) s.name],
        if (tags.isNotEmpty) 'tag': tags,
        if (query.sort != TranscriptionSort.newest) 'sort': query.sort.name,
      },
    ).toString();
  }

  /// The tag library (for the filter). Only the names so far; the Tags screen brings the rest.
  @override
  Future<List<Tag>> tags() async {
    final body = await _client.getJson('/tags');
    if (body is! List) throw const GatewayException('Expected a list of tags');
    return [
      for (final entry in body)
        Tag((entry as Map<String, dynamic>)['label'] as String, colorForLabel(entry['label'] as String)),
    ];
  }

  @override
  Future<TranscriptionDetail?> get(String id) => _notYet('Opening a transcription', 3);

  @override
  Future<void> rename(String id, String title) => _notYet('Renaming', 3);

  @override
  Future<Transcription> upload(UploadRequest request) => _notYet('Uploading', 7);

  @override
  Future<Tag> addTag(String id, String label) => _notYet('Adding a tag', 4);

  @override
  Future<void> removeTag(String id, String label) => _notYet('Removing a tag', 4);

  @override
  Future<List<TagEntry>> tagLibrary() => _notYet('The tag library', 4);

  @override
  Future<Tag> createTag(String label, String description) => _notYet('Creating a tag', 4);

  @override
  Future<void> updateTag(String label, {required String newLabel, required String description}) =>
      _notYet('Editing a tag', 4);

  @override
  Future<int> deleteTag(String label) => _notYet('Deleting a tag', 4);

  @override
  Future<List<Speaker>> speakers() => _notYet('The speaker library', 5);

  @override
  Future<List<SpeakerEntry>> speakerLibrary() => _notYet('The speaker library', 5);

  @override
  Future<Speaker> createSpeaker(String name, String description) => _notYet('Creating a speaker', 5);

  @override
  Future<void> updateSpeaker(String name, {required String newName, required String description}) =>
      _notYet('Editing a speaker', 5);

  @override
  Future<int> deleteSpeaker(String name) => _notYet('Deleting a speaker', 5);

  @override
  Future<Speaker> assignSpeaker(String id, Set<int> segmentIndexes, String name) =>
      _notYet('Assigning a speaker', 6);

  @override
  Future<void> acceptAllSuggestions(String id) => _notYet('AI suggestions', 8);

  @override
  Future<void> dismissAllSuggestions(String id) => _notYet('AI suggestions', 8);

  @override
  Future<void> acceptTagSuggestion(String id, String label) => _notYet('AI suggestions', 8);

  @override
  Future<void> rejectTagSuggestion(String id, String label) => _notYet('AI suggestions', 8);

  @override
  Future<void> acceptSpeakerSuggestion(String id, int slot) => _notYet('AI suggestions', 8);

  @override
  Future<void> rejectSpeakerSuggestion(String id, int slot) => _notYet('AI suggestions', 8);

  Future<T> _notYet<T>(String what, int iteration) => Future.error(NotIntegratedException(what, iteration));
}

/// One entry of the gateway's `GET /transcriptions`. Length, speakers and progress are optional there: they
/// appear as the transcription gets far enough.
Transcription _transcriptionFromJson(Map<String, dynamic> json) {
  final title = (json['title'] as String?)?.trim();
  return Transcription(
    id: json['job_id'] as String,
    title: title == null || title.isEmpty ? json['file_name'] as String : title,
    uploadedAt: DateTime.parse(json['created_at'] as String).toLocal(),
    status: _statusFromJson(json['status'] as String?),
    duration: _secondsFromJson(json['audio_duration_s']),
    speakerCount: (json['speaker_count'] as num?)?.toInt() ?? 0,
    processingPct: (json['processing_pct'] as num?)?.toInt(),
    processingPhase: _phaseFromJson(json['processing_phase'] as String?),
    tags: [
      for (final label in (json['tags'] as List<dynamic>? ?? const []))
        if (label is String && label.isNotEmpty) Tag(label, colorForLabel(label)),
    ],
  );
}

/// Seconds (a JSON number, possibly fractional) as a [Duration]; null when absent.
Duration? _secondsFromJson(Object? seconds) =>
    seconds is num ? Duration(milliseconds: (seconds * 1000).round()) : null;

ProcessingPhase? _phaseFromJson(String? phase) => switch (phase) {
      'transcribing' => ProcessingPhase.transcribing,
      'diarizing' => ProcessingPhase.diarizing,
      'summarizing' => ProcessingPhase.summarizing,
      _ => null,
    };

/// The gateway's statuses are `pending`, `processing`, `summarizing`, `done` and `failed`; the list shows
/// the summary step as part of processing.
TranscriptionStatus _statusFromJson(String? status) => switch (status) {
      'done' => TranscriptionStatus.done,
      'processing' || 'summarizing' => TranscriptionStatus.processing,
      'failed' => TranscriptionStatus.failed,
      _ => TranscriptionStatus.pending,
    };

/// A color for a tag that has none of its own: the gateway does not send one (`docs/api-gaps.md` decides that
/// clients derive it from the name). The same label always gets the same color.
Color colorForLabel(String label) {
  var hash = 0;
  for (final unit in label.toLowerCase().codeUnits) {
    hash = (hash * 31 + unit) & 0x7fffffff;
  }
  return libraryPalette[hash % libraryPalette.length];
}
