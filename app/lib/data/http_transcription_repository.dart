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
/// list works, and it is a stopgap: the gateway's list has neither duration, progress, nor speakers yet and
/// cannot filter, so filtering and sorting happen here. Everything else throws [NotIntegratedException].
class HttpTranscriptionRepository implements TranscriptionRepository {
  HttpTranscriptionRepository(this._client);

  final GatewayClient _client;

  @override
  Future<TranscriptionPage> list(TranscriptionQuery query, {int page = 0, int size = transcriptionPageSize}) async {
    if (_gatewayCanAnswer(query)) {
      final body = await _client.getJson('/transcriptions?page=$page&size=$size');
      if (body is! Map<String, dynamic> || body['items'] is! List) {
        throw const GatewayException('Expected a page of transcriptions');
      }
      return TranscriptionPage(
        [for (final item in body['items'] as List<dynamic>) _transcriptionFromJson(item as Map<String, dynamic>)],
        hasNext: body['has_next'] == true,
      );
    }

    final items = filterTranscriptions(await _fetchAll(), query);
    switch (query.sort) {
      case TranscriptionSort.newest:
        items.sort((a, b) => b.uploadedAt.compareTo(a.uploadedAt));
      case TranscriptionSort.oldest:
        items.sort((a, b) => a.uploadedAt.compareTo(b.uploadedAt));
      case TranscriptionSort.longest:
        items.sort((a, b) => (b.duration ?? Duration.zero).compareTo(a.duration ?? Duration.zero));
      case TranscriptionSort.name:
        items.sort((a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()));
    }
    return sliceTranscriptions(items, page, size);
  }

  /// The gateway pages the plain list, newest first. It cannot search, filter or sort differently yet
  /// (integration iteration 2c), so those queries are answered here from the whole list.
  bool _gatewayCanAnswer(TranscriptionQuery query) =>
      query.search.trim().isEmpty && query.statuses.isEmpty && query.tags.isEmpty && query.sort == TranscriptionSort.newest;

  /// Every transcription of the user (the unpaged array), newest first as the gateway sends them.
  Future<List<Transcription>> _fetchAll() async {
    final body = await _client.getJson('/transcriptions');
    if (body is! List) throw const GatewayException('Expected a list of transcriptions');
    return [for (final item in body) _transcriptionFromJson(item as Map<String, dynamic>)];
  }

  /// The library, until it has an endpoint of its own: the tags in use, in order of appearance.
  @override
  Future<List<Tag>> tags() async {
    final seen = <String, Tag>{};
    for (final item in await _fetchAll()) {
      for (final tag in item.tags) {
        seen.putIfAbsent(tag.label, () => tag);
      }
    }
    return seen.values.toList();
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

/// One entry of the gateway's `GET /transcriptions`.
Transcription _transcriptionFromJson(Map<String, dynamic> json) {
  final title = (json['title'] as String?)?.trim();
  return Transcription(
    id: json['job_id'] as String,
    title: title == null || title.isEmpty ? json['file_name'] as String : title,
    uploadedAt: DateTime.parse(json['created_at'] as String).toLocal(),
    status: _statusFromJson(json['status'] as String?),
    tags: [
      for (final label in (json['tags'] as List<dynamic>? ?? const []))
        if (label is String && label.isNotEmpty) Tag(label, colorForLabel(label)),
    ],
  );
}

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
