import 'dart:ui' show Color;

import '../models/speaker.dart';
import '../models/speaker_entry.dart';
import '../models/suggestions.dart';
import '../models/tag.dart';
import '../models/tag_entry.dart';
import '../models/transcription.dart';
import '../models/transcription_detail.dart';
import '../models/upload.dart';
import 'gateway_client.dart';
import 'transcription_repository.dart';

/// The real data source: the gateway over HTTP (`docs/local-backend-integration.md`).
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

  /// `/transcriptions?page=0&size=30&q=…&status=…&tag=…&speaker=…&sort=…`: only what narrows the list or
  /// changes its order is sent, and lists are sent as repeated parameters (a name may contain a comma).
  static String _listPath(TranscriptionQuery query, int page, int size) {
    final search = query.search.trim();
    final tags = query.tags.toList()..sort();
    final speakers = query.speakers.toList()..sort();
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
        if (speakers.isNotEmpty) 'speaker': speakers,
        if (query.sort != TranscriptionSort.newest) 'sort': query.sort.name,
      },
    ).toString();
  }

  /// The tags of the library, for the filter and the "Add tag" menu.
  @override
  Future<List<Tag>> tags() async => [for (final entry in (await _library()).entries) entry.tag];

  @override
  Future<TagLibrary> tagLibrary() async => _library();

  Future<TagLibrary> _library() async {
    final body = await _client.getJson('/tags');
    if (body is! Map<String, dynamic> || body['tags'] is! List) throw const GatewayException('Expected the tag library');
    return TagLibrary(
      entries: [
        for (final entry in body['tags'] as List<dynamic>)
          TagEntry(
            tag: _tagFromJson(entry as Map<String, dynamic>),
            description: entry['description'] as String? ?? '',
            transcriptions: [for (final title in (entry['sample_titles'] as List<dynamic>? ?? const [])) title as String],
            count: (entry['transcription_count'] as num?)?.toInt() ?? 0,
          ),
      ],
      taggedTranscriptions: (body['tagged_transcriptions'] as num?)?.toInt() ?? 0,
    );
  }

  /// The gateway names tags by id; the screens know them by label (unique per user), so the id is looked up
  /// when a tag is changed, deleted or taken off. Null when no such tag exists any more.
  Future<String?> _tagId(String label) async {
    final body = await _client.getJson('/tags');
    if (body is! Map<String, dynamic> || body['tags'] is! List) throw const GatewayException('Expected the tag library');
    for (final entry in body['tags'] as List<dynamic>) {
      if ((entry as Map<String, dynamic>)['label'] == label) return entry['id'] as String;
    }
    return null;
  }

  @override
  Future<Tag> createTag(String label, String description) async {
    final body = await _client.postJson('/tags', {'label': label, 'description': description});
    return _tagFromJson(body as Map<String, dynamic>);
  }

  @override
  Future<void> updateTag(String label, {required String newLabel, required String description}) async {
    final id = await _tagId(label);
    if (id == null) return;
    await _client.putJson('/tags/${Uri.encodeComponent(id)}', {'label': newLabel, 'description': description});
  }

  @override
  Future<int> deleteTag(String label) async {
    final id = await _tagId(label);
    if (id == null) return 0;
    final body = await _client.deleteJson('/tags/${Uri.encodeComponent(id)}');
    return ((body as Map<String, dynamic>)['affected_transcriptions'] as num?)?.toInt() ?? 0;
  }

  /// Puts the tag on the transcription; the gateway creates it in the library when the label is new.
  @override
  Future<Tag> addTag(String id, String label) async {
    final body = await _client.postJson('/transcriptions/${Uri.encodeComponent(id)}/tags', {'label': label});
    return _tagFromJson(body as Map<String, dynamic>);
  }

  @override
  Future<void> removeTag(String id, String label) async {
    final tagId = await _tagId(label);
    if (tagId == null) return;
    await _client.deleteJson('/transcriptions/${Uri.encodeComponent(id)}/tags/${Uri.encodeComponent(tagId)}');
  }

  /// `GET /transcriptions/{id}`. The gateway answers 404 for an id that does not exist or is not the user's;
  /// a transcription that is not done yet has no transcript to show, which the detail page treats the same.
  @override
  Future<TranscriptionDetail?> get(String id) async {
    final Object? body;
    try {
      body = await _client.getJson('/transcriptions/${Uri.encodeComponent(id)}');
    } on GatewayException catch (e) {
      if (e.statusCode == 404) return null;
      rethrow;
    }
    if (body is! Map<String, dynamic>) throw const GatewayException('Expected a transcription');
    final detail = _detailFromJson(body);
    return detail.item.status == TranscriptionStatus.done ? detail : null;
  }

  @override
  Future<String?> transcriptText(String id) async {
    try {
      return await _client.getText('/transcript?jobId=${Uri.encodeQueryComponent(id)}');
    } on GatewayException catch (e) {
      if (e.statusCode == 404) return null;
      rethrow;
    }
  }

  @override
  Future<String?> audioUrl(String id) async {
    try {
      final body = await _client.postJson('/transcriptions/${Uri.encodeComponent(id)}/audio-token', {});
      final token = (body as Map<String, dynamic>?)?['token'] as String?;
      if (token == null) return null;
      return '${_client.baseUrl}/transcriptions/${Uri.encodeComponent(id)}/audio'
          '?token=${Uri.encodeQueryComponent(token)}';
    } on GatewayException catch (e) {
      if (e.statusCode == 404) return null;
      if (e.statusCode == 410) throw const AudioUnavailableException();
      rethrow;
    }
  }

  @override
  Future<void> rename(String id, String title) =>
      _client.patchJson('/jobs/${Uri.encodeComponent(id)}/title', {'title': title});

  /// The model every upload asks for. The dialog has no choice of it yet: which models a user may use should depend
  /// on their plan (see docs/local-backend-integration.md), and that is not built.
  static const _uploadModel = 'accurate';

  /// `POST /transcriptions`: the file and the options of the dialog as one multipart form.
  @override
  Future<UploadOutcome> upload(
    UploadRequest request, {
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  }) async {
    final speakers = request.speakers;
    final body = await _client.postFile(
      '/transcriptions',
      fields: {
        'language': request.language.code,
        'summary': request.summary.name, // the names are the gateway's modes: off, auto, brief, concise, full
        'diarize': '${speakers.diarize}',
        if (speakers.diarize && speakers.expected != null) 'num_speakers': '${speakers.expected}',
        'model': _uploadModel,
      },
      file: request.file,
      onProgress: onProgress,
      cancellation: cancellation,
    );
    if (body is! Map<String, dynamic> || body['item'] is! Map<String, dynamic>) {
      throw const GatewayException('Expected the new transcription');
    }
    return UploadOutcome(_transcriptionFromJson(body['item'] as Map<String, dynamic>), duplicate: body['duplicate'] == true);
  }


  /// The speaker library, for the Speaker Picker of the detail page. (Choosing one there is connected in
  /// iteration 6.)
  @override
  Future<List<Speaker>> speakers() async => [for (final entry in await _speakerEntries()) entry.speaker];

  @override
  Future<List<SpeakerEntry>> speakerLibrary() => _speakerEntries();

  Future<List<SpeakerEntry>> _speakerEntries() async {
    final body = await _client.getJson('/speakers');
    if (body is! Map<String, dynamic> || body['speakers'] is! List) {
      throw const GatewayException('Expected the speaker library');
    }
    return [
      for (final entry in body['speakers'] as List<dynamic>)
        SpeakerEntry(
          speaker: _speakerFromJson(entry as Map<String, dynamic>),
          description: entry['description'] as String? ?? '',
          transcriptions: [for (final title in (entry['sample_titles'] as List<dynamic>? ?? const [])) title as String],
          totalDuration: _secondsFromJson(entry['speaking_s']) ?? Duration.zero,
          count: (entry['transcription_count'] as num?)?.toInt() ?? 0,
        ),
    ];
  }

  /// The gateway names speakers by id, the screens by name (unique per user, whatever the case), so the id is
  /// looked up when a speaker is changed or deleted. Null when no such speaker exists any more.
  Future<String?> _speakerId(String name) async {
    final body = await _client.getJson('/speakers');
    if (body is! Map<String, dynamic> || body['speakers'] is! List) {
      throw const GatewayException('Expected the speaker library');
    }
    for (final entry in body['speakers'] as List<dynamic>) {
      if ((entry as Map<String, dynamic>)['name'] == name) return entry['id'] as String;
    }
    return null;
  }

  @override
  Future<Speaker> createSpeaker(String name, String description) async {
    final body = await _client.postJson('/speakers', {'name': name, 'description': description});
    return _speakerFromJson(body as Map<String, dynamic>);
  }

  @override
  Future<void> updateSpeaker(String name, {required String newName, required String description}) async {
    final id = await _speakerId(name);
    if (id == null) return;
    await _client.putJson('/speakers/${Uri.encodeComponent(id)}', {'name': newName, 'description': description});
  }

  @override
  Future<int> deleteSpeaker(String name) async {
    final id = await _speakerId(name);
    if (id == null) return 0;
    final body = await _client.deleteJson('/speakers/${Uri.encodeComponent(id)}');
    return ((body as Map<String, dynamic>)['affected_transcriptions'] as num?)?.toInt() ?? 0;
  }

  /// `PUT /transcriptions/{id}/speakers`: the picker's choice. The gateway settles the voice or marks the
  /// phrases (see the repository interface), creating the speaker in the library when the name is new.
  @override
  Future<Speaker> assignSpeaker(String id, Set<int> segmentIndexes, String name, {bool allBySpeaker = false}) async {
    final body = await _client.putJson('/transcriptions/${Uri.encodeComponent(id)}/speakers', {
      'scope': allBySpeaker ? 'all' : 'segment',
      'segment_indexes': segmentIndexes.toList()..sort(),
      'name': name,
    });
    return _speakerFromJson(body as Map<String, dynamic>);
  }

  @override
  Future<void> regenerateSummary(String id) async {
    await _client.postJson('/transcriptions/${Uri.encodeComponent(id)}/regenerate-summary', const <String, Object>{});
  }

  // What the AI proposed is settled with `POST /transcriptions/{id}/suggestions/...`. The gateway answers what
  // is still proposed; the controller reads the transcription again, so the answer is not used.

  @override
  Future<void> acceptAllSuggestions(String id) => _suggestion(id, 'accept-all');

  @override
  Future<void> dismissAllSuggestions(String id) => _suggestion(id, 'dismiss-all');

  @override
  Future<void> acceptTagSuggestion(String id, String label) => _suggestion(id, 'tags/${Uri.encodeComponent(label)}/accept');

  @override
  Future<void> rejectTagSuggestion(String id, String label) => _suggestion(id, 'tags/${Uri.encodeComponent(label)}/reject');

  @override
  Future<void> acceptSpeakerSuggestion(String id, int slot) => _suggestion(id, 'speakers/${_voiceLabel(slot)}/accept');

  @override
  Future<void> rejectSpeakerSuggestion(String id, int slot) => _suggestion(id, 'speakers/${_voiceLabel(slot)}/reject');

  Future<void> _suggestion(String id, String action) async {
    await _client.postJson('/transcriptions/${Uri.encodeComponent(id)}/suggestions/$action', const <String, Object>{});
  }
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
      for (final tag in (json['tags'] as List<dynamic>? ?? const []))
        if (tag is Map<String, dynamic>) _tagFromJson(tag),
    ],
  );
}

/// The gateway's `GET /transcriptions/{id}`. Fields that are not known are left out of the JSON.
TranscriptionDetail _detailFromJson(Map<String, dynamic> json) {
  final segments = [
    for (final s in (json['segments'] as List<dynamic>? ?? const []))
      Segment(
        start: _secondsFromJson((s as Map<String, dynamic>)['start']) ?? Duration.zero,
        speakerSlot: _slotFromLabel(s['speaker'] as String?),
        text: s['text'] as String? ?? '',
      ),
  ];
  final fileName = json['file_name'] as String;
  final title = (json['title'] as String?)?.trim();
  return TranscriptionDetail(
    item: Transcription(
      id: json['job_id'] as String,
      title: title == null || title.isEmpty ? fileName : title,
      uploadedAt: DateTime.parse(json['created_at'] as String).toLocal(),
      status: _statusFromJson(json['status'] as String?),
      duration: _secondsFromJson(json['audio_duration_s']),
      speakerCount: {for (final s in segments) if (s.speakerSlot != null) s.speakerSlot}.length,
      tags: [
        for (final tag in (json['tags'] as List<dynamic>? ?? const []))
          if (tag is Map<String, dynamic>) _tagFromJson(tag),
      ],
    ),
    segments: segments,
    // Voices the user has named (the row of chips), and single phrases set to somebody by hand.
    speakers: {
      for (final v in (json['voices'] as List<dynamic>? ?? const []))
        ?_slotFromLabel((v as Map<String, dynamic>)['voice'] as String?): _speakerFromJson(v['speaker'] as Map<String, dynamic>),
    },
    segmentSpeakers: {
      for (final (i, s) in (json['segments'] as List<dynamic>? ?? const []).indexed)
        if ((s as Map<String, dynamic>)['assigned_speaker'] case final own?)
          i: _speakerFromJson(own as Map<String, dynamic>),
    },
    suggestions: _suggestionsFromJson(json['suggestions']),
    summary: json['summary'] as String?,
    originalFilename: fileName,
    fileSizeBytes: (json['file_size_bytes'] as num?)?.toInt() ?? 0,
    language: (json['language'] as String?)?.toUpperCase(),
    wordCount: (json['word_count'] as num?)?.toInt(),
    regeneration: _regenerationFromJson(json['summary_regeneration']),
    regeneratedAt: DateTime.tryParse(((json['summary_regeneration'] as Map<String, dynamic>?)?['at'] as String?) ?? '')?.toLocal(),
  );
}

SummaryRegeneration _regenerationFromJson(Object? json) => switch ((json as Map<String, dynamic>?)?['state']) {
      'available' => SummaryRegeneration.available,
      'running' => SummaryRegeneration.running,
      'used' => SummaryRegeneration.used,
      _ => SummaryRegeneration.unavailable,
    };

/// The diarizer names its voices `SPEAKER_00`, `SPEAKER_01`, ...; the page counts slots from 1.
/// Null for a segment without a speaker (no diarization) or with a label of another shape.
int? _slotFromLabel(String? label) {
  final digits = label == null ? null : RegExp(r'(\d+)$').firstMatch(label)?.group(1);
  return digits == null ? null : int.parse(digits) + 1;
}

/// The label of the diarizer voice behind a page slot: slot 1 is `SPEAKER_00` (the reverse of [_slotFromLabel]).
String _voiceLabel(int slot) => 'SPEAKER_${(slot - 1).toString().padLeft(2, '0')}';

/// What the AI proposes, as the gateway sends it: `{title?, tags: [{label, color}], speakers: [{voice, name, color}]}`.
/// Null when the transcription has nothing (left) to settle.
Suggestions? _suggestionsFromJson(Object? json) {
  if (json is! Map<String, dynamic>) return null;
  final suggestions = Suggestions(
    title: json['title'] as String?,
    tags: [
      for (final tag in (json['tags'] as List<dynamic>? ?? const [])) _tagFromJson(tag as Map<String, dynamic>),
    ],
    speakers: {
      for (final s in (json['speakers'] as List<dynamic>? ?? const []))
        ?_slotFromLabel((s as Map<String, dynamic>)['voice'] as String?): _speakerFromJson(s),
    },
  );
  return suggestions.isEmpty ? null : suggestions;
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

/// A tag as the gateway describes it (`{id, label, color}`); the color is `#RRGGBB`, worked out by the
/// gateway from the label.
Tag _tagFromJson(Map<String, dynamic> json) => Tag(json['label'] as String, _colorFromJson(json['color'] as String?));

/// Grey, for a tag the gateway sent without a usable color.
const _fallbackTagColor = Color(0xFF64748B);

Color _colorFromJson(String? hex) {
  final value = hex != null && RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(hex) ? int.parse(hex.substring(1), radix: 16) : null;
  return value == null ? _fallbackTagColor : Color(0xFF000000 | value);
}

/// A speaker as the gateway describes it (`{id, name, color}`), the color worked out by the gateway.
Speaker _speakerFromJson(Map<String, dynamic> json) => Speaker(json['name'] as String, _colorFromJson(json['color'] as String?));
