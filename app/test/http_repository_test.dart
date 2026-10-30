import 'dart:convert';

import 'dart:ui' show Color;

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/data/http_transcription_repository.dart';
import 'package:dictara_web/data/transcription_repository.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/transcription_detail.dart';
import 'package:dictara_web/models/upload.dart';
import 'package:dictara_web/services/multipart_uploader.dart';

/// A tag as the gateway describes it in the list and in the detail.
Map<String, String> _tag(String label, {String color = '#2563EB'}) => {'id': 'id-$label', 'label': label, 'color': color};

/// What `GET /transcriptions` of the gateway returns (`snake_case`, newest first).
final List<Map<String, dynamic>> _listJson = [
  {
    'job_id': '3f1c2f0e-0000-4000-8000-000000000003',
    'file_name': 'standup.mp3',
    'title': 'Daily Standup',
    'created_at': '2026-09-20T16:36:21.609254Z',
    'status': 'done',
    'tags': [_tag('meeting'), _tag('standup')],
  },
  {
    'job_id': '3f1c2f0e-0000-4000-8000-000000000002',
    'file_name': 'lecture.wav',
    'title': 'lecture.wav',
    'created_at': '2026-09-19T10:00:00Z',
    'status': 'summarizing',
    'tags': <String>[],
  },
  {
    'job_id': '3f1c2f0e-0000-4000-8000-000000000001',
    'file_name': 'memo.m4a',
    'title': 'Voice memo',
    'created_at': '2026-09-18T08:30:00Z',
    'status': 'failed',
    'tags': [_tag('meeting')],
  },
  {
    'job_id': '3f1c2f0e-0000-4000-8000-000000000000',
    'file_name': 'queued.mp3',
    'title': '  ',
    'created_at': '2026-09-17T08:30:00Z',
    'status': 'pending',
    'tags': <String>[],
  },
];

http.Response _json(Object body, {int status = 200}) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

HttpTranscriptionRepository _repo(MockClient client) =>
    HttpTranscriptionRepository(GatewayClient(httpClient: client));

/// A gateway that serves [all] as the plain array, or as pages when asked with page/size.
MockClient _gateway(List<Map<String, dynamic>> all, {List<Uri>? requests}) => MockClient((request) async {
  requests?.add(request.url);
  final query = request.url.queryParameters;
  if (!query.containsKey('page') && !query.containsKey('size')) return _json(all);
  final page = int.parse(query['page'] ?? '0');
  final size = int.parse(query['size'] ?? '30');
  return _json({
    'items': all.skip(page * size).take(size).toList(),
    'page': page,
    'size': size,
    'has_next': (page + 1) * size < all.length,
  });
});

void main() {
  _pagingTests();
  _fieldTests();
  _queryTests();

  group('GatewayClient', () {
    test('asks the given path under /api and decodes JSON', () async {
      Uri? asked;
      final client = GatewayClient(
        httpClient: MockClient((request) async {
          asked = request.url;
          return _json({'status': 'ok'});
        }),
      );

      final body = await client.getJson('/health');

      expect(asked.toString(), '/api/health');
      expect(body, {'status': 'ok'});
    });

    test('a different base URL is honoured', () async {
      Uri? asked;
      final client = GatewayClient(
        baseUrl: 'http://localhost:8080',
        httpClient: MockClient((request) async {
          asked = request.url;
          return _json([]);
        }),
      );
      await client.getJson('/transcriptions');
      expect(asked.toString(), 'http://localhost:8080/transcriptions');
    });

    test('non-ASCII text survives', () async {
      final client = GatewayClient(
        httpClient: MockClient((request) async => http.Response.bytes(
          utf8.encode('{"title":"Разговор с клиентом — 🎧"}'),
          200,
        )),
      );
      expect(await client.getJson('/x'), {'title': 'Разговор с клиентом — 🎧'});
    });

    test('an error status carries the message Spring sends', () async {
      final client = GatewayClient(
        httpClient: MockClient((request) async => _json({'message': 'Job not found'}, status: 404)),
      );

      await expectLater(
        client.getJson('/jobs/x'),
        throwsA(
          isA<GatewayException>()
              .having((e) => e.statusCode, 'statusCode', 404)
              .having((e) => e.message, 'message', 'Job not found'),
        ),
      );
    });

    test('an error without a JSON body falls back to the status text', () async {
      final client = GatewayClient(
        httpClient: MockClient((request) async => http.Response('<html>Bad Gateway</html>', 502, reasonPhrase: 'Bad Gateway')),
      );

      await expectLater(
        client.getJson('/x'),
        throwsA(isA<GatewayException>().having((e) => e.message, 'message', 'Bad Gateway')),
      );
    });

    test('no connection becomes a GatewayException without a status', () async {
      final client = GatewayClient(
        httpClient: MockClient((request) async => throw http.ClientException('Connection refused')),
      );

      await expectLater(
        client.getJson('/x'),
        throwsA(
          isA<GatewayException>()
              .having((e) => e.statusCode, 'statusCode', isNull)
              .having((e) => e.message, 'message', contains('Could not reach the server')),
        ),
      );
    });

    test('a body that is not JSON is reported', () async {
      final client = GatewayClient(httpClient: MockClient((request) async => http.Response('nope', 200)));
      await expectLater(client.getJson('/x'), throwsA(isA<GatewayException>()));
    });

    test('an empty body is null', () async {
      final client = GatewayClient(httpClient: MockClient((request) async => http.Response('', 200)));
      expect(await client.getJson('/x'), isNull);
    });
  });

  group('HttpTranscriptionRepository.list', () {
    final repo = _repo(_gateway(_listJson));

    test('maps the gateway fields to the model', () async {
      final items = (await repo.list(const TranscriptionQuery())).items;

      final first = items.first;
      expect(first.id, '3f1c2f0e-0000-4000-8000-000000000003');
      expect(first.title, 'Daily Standup');
      expect(first.status, TranscriptionStatus.done);
      expect(first.tags.map((t) => t.label), ['meeting', 'standup']);
      expect(first.uploadedAt.toUtc(), DateTime.utc(2026, 9, 20, 16, 36, 21, 609, 254));
      // The list has none of these yet.
      expect(first.duration, isNull);
      expect(first.processingPct, isNull);
      expect(first.speakerCount, 0);
    });

    test('recorded_at is read when the gateway has it, and is null when it is left out', () async {
      final repo = _repo(
        _gateway([
          {..._listJson[0], 'recorded_at': '2026-06-08T17:39:00Z'},
          _listJson[1],
        ]),
      );
      final items = (await repo.list(const TranscriptionQuery())).items;

      expect(items[0].recordedAt!.toUtc(), DateTime.utc(2026, 6, 8, 17, 39));
      expect(items[1].recordedAt, isNull);
      // The upload time is a different thing and is never replaced by it.
      expect(items[0].uploadedAt.toUtc(), DateTime.utc(2026, 9, 20, 16, 36, 21, 609, 254));
    });

    test('times from the gateway are shown in the zone of the viewer', () async {
      final item = (await _repo(_gateway([{..._listJson[0], 'recorded_at': '2026-06-08T17:39:00Z'}])).list(const TranscriptionQuery())).items.single;

      expect(item.recordedAt!.isUtc, isFalse);
      expect(item.uploadedAt.isUtc, isFalse);
    });

    test('statuses are mapped, summarizing counts as processing', () async {
      final byId = {for (final i in (await repo.list(const TranscriptionQuery())).items) i.title: i.status};

      expect(byId['Daily Standup'], TranscriptionStatus.done);
      expect(byId['lecture.wav'], TranscriptionStatus.processing); // was "summarizing"
      expect(byId['Voice memo'], TranscriptionStatus.failed);
    });

    test('a blank title falls back to the file name', () async {
      final items = (await repo.list(const TranscriptionQuery())).items;
      expect(items.map((i) => i.title), contains('queued.mp3'));
    });

    test('an unknown status is treated as pending', () async {
      final odd = _repo(
        _gateway([
          {
            'job_id': 'x',
            'file_name': 'a.mp3',
            'title': 'a',
            'created_at': '2026-01-01T00:00:00Z',
            'status': 'something-new',
            'tags': <String>[],
          },
        ]),
      );
      expect((await odd.list(const TranscriptionQuery())).items.single.status, TranscriptionStatus.pending);
    });

    test('an empty list is fine', () async {
      final empty = _repo(_gateway([]));
      expect((await empty.list(const TranscriptionQuery())).items, isEmpty);
    });

    test('the entries keep the order the gateway gave them', () async {
      final items = (await repo.list(const TranscriptionQuery())).items;
      expect(items.map((i) => i.title), ['Daily Standup', 'lecture.wav', 'Voice memo', 'queued.mp3']);
    });

    test('an answer that is not a page is an error', () async {
      final broken = _repo(MockClient((request) async => _json({'oops': true})));
      await expectLater(broken.list(const TranscriptionQuery()), throwsA(isA<GatewayException>()));
    });

    test('a server error reaches the caller', () async {
      final down = _repo(MockClient((request) async => _json({'message': 'boom'}, status: 500)));
      await expectLater(
        down.list(const TranscriptionQuery()),
        throwsA(isA<GatewayException>().having((e) => e.statusCode, 'statusCode', 500)),
      );
    });
  });

  group('tags', () {
    const libraryJson = <String, dynamic>{
      'tags': [
        {
          'id': 'a',
          'label': 'meeting',
          'color': '#2563EB',
          'description': 'Regular meetings',
          'transcription_count': 5,
          'sample_titles': ['New one', 'Older one'],
          'samples': [
            {'job_id': 'job-new', 'title': 'New one'},
            {'job_id': 'job-old', 'title': 'Older one'},
          ],
        },
        {'id': 'b', 'label': 'unused', 'color': '#059669', 'transcription_count': 0, 'sample_titles': <String>[], 'samples': <Map<String, String>>[]},
      ],
      'tagged_transcriptions': 4,
    };

    /// Answers by method and path, and records what was asked.
    (HttpTranscriptionRepository, List<http.Request>) repoWith(Map<String, http.Response Function(http.Request)> routes) {
      final seen = <http.Request>[];
      final repo = _repo(MockClient((request) async {
        seen.add(request);
        final route = routes['${request.method} ${request.url.path}'];
        return route == null ? http.Response('no route', 500) : route(request);
      }));
      return (repo, seen);
    }

    test('the library comes from /tags: description, count, latest titles, colors from the gateway', () async {
      final (repo, seen) = repoWith({'GET /api/tags': (_) => _json(libraryJson)});

      final library = await repo.tagLibrary();

      expect(seen.single.url.toString(), '/api/tags');
      expect(library.taggedTranscriptions, 4);
      final meeting = library.entries.first;
      expect(meeting.tag.label, 'meeting');
      expect(meeting.tag.color, const Color(0xFF2563EB));
      expect(meeting.description, 'Regular meetings');
      expect(meeting.count, 5);
      expect(meeting.transcriptions.map((t) => (t.id, t.title)), [('job-new', 'New one'), ('job-old', 'Older one')]); // only the latest two: count says the rest, and each carries the id that opens it
      final unused = library.entries.last;
      expect(unused.description, '');
      expect(unused.count, 0);
      expect(unused.transcriptions, isEmpty);
    });

    test('a library answer without samples (an older gateway) simply has no chips', () async {
      final (repo, _) = repoWith({'GET /api/tags': (_) => _json({'tags': [{'id': 'a', 'label': 'meeting', 'color': '#2563EB', 'transcription_count': 3, 'sample_titles': ['x']}], 'tagged_transcriptions': 3})});

      final entry = (await repo.tagLibrary()).entries.single;

      expect(entry.transcriptions, isEmpty);
      expect(entry.count, 3);
    });

    test('tags() are the library entries, unused ones too', () async {
      final (repo, _) = repoWith({'GET /api/tags': (_) => _json(libraryJson)});

      expect((await repo.tags()).map((t) => t.label), ['meeting', 'unused']);
    });

    test('tags() ask for the brief view, and read it: only id, label, color and description', () async {
      final (repo, seen) = repoWith({
        'GET /api/tags': (_) => _json({
          'tags': [
            {'id': 'a', 'label': 'meeting', 'color': '#2563EB', 'description': 'Regular meetings'},
            {'id': 'b', 'label': 'unused', 'color': '#059669'},
          ],
        }),
      });

      final tags = await repo.tags();

      expect(seen.single.url.queryParameters, {'view': 'brief'});
      expect(tags.map((t) => t.label), ['meeting', 'unused']);
      expect(tags.first.color, const Color(0xFF2563EB));
    });

    test('the tag library page still asks for the full list', () async {
      final (repo, seen) = repoWith({'GET /api/tags': (_) => _json(libraryJson)});

      await repo.tagLibrary();

      expect(seen.single.url.queryParameters.containsKey('view'), isFalse);
    });

    test('an answer that is not the library is an error', () async {
      final (repo, _) = repoWith({'GET /api/tags': (_) => _json(['oops'])});

      await expectLater(repo.tags(), throwsA(isA<GatewayException>()));
      await expectLater(repo.tagLibrary(), throwsA(isA<GatewayException>()));
    });

    test('a color the gateway sends wrongly becomes grey instead of breaking the list', () async {
      final (repo, _) = repoWith({
        'GET /api/tags': (_) => _json({
              'tags': [
                {'id': 'a', 'label': 'x', 'color': 'blue'},
                {'id': 'b', 'label': 'y'},
              ],
              'tagged_transcriptions': 0,
            }),
      });

      expect((await repo.tags()).map((t) => t.color), [const Color(0xFF64748B), const Color(0xFF64748B)]);
    });

    test('a tag is created with POST /tags', () async {
      final (repo, seen) = repoWith({'POST /api/tags': (_) => _json(_tag('fresh', color: '#8F13AE'), status: 201)});

      final tag = await repo.createTag('fresh', 'made here');

      expect(seen.single.method, 'POST');
      expect(jsonDecode(seen.single.body), {'label': 'fresh', 'description': 'made here'});
      expect(tag.label, 'fresh');
      expect(tag.color, const Color(0xFF8F13AE));
    });

    test('a taken name comes back as the gateways own message', () async {
      final (repo, _) = repoWith({
        'POST /api/tags': (_) => _json({'message': 'A tag with this name already exists'}, status: 409),
      });

      await expectLater(
        repo.createTag('meeting', ''),
        throwsA(isA<GatewayException>()
            .having((e) => e.statusCode, 'statusCode', 409)
            .having((e) => e.message, 'message', contains('already exists'))),
      );
    });

    test('editing looks the id up by label and PUTs the new name and description', () async {
      final (repo, seen) = repoWith({
        'GET /api/tags': (_) => _json(libraryJson),
        'PUT /api/tags/a': (_) => _json(_tag('sync')),
      });

      await repo.updateTag('meeting', newLabel: 'sync', description: 'new text');

      expect(seen.map((r) => '${r.method} ${r.url.path}'), ['GET /api/tags', 'PUT /api/tags/a']);
      expect(jsonDecode(seen.last.body), {'label': 'sync', 'description': 'new text'});
    });

    test('editing or deleting a tag that no longer exists does nothing', () async {
      final (repo, seen) = repoWith({'GET /api/tags': (_) => _json(libraryJson)});

      await repo.updateTag('gone', newLabel: 'x', description: '');
      expect(await repo.deleteTag('gone'), 0);

      expect(seen.map((r) => r.method).toSet(), {'GET'});
    });

    test('deleting returns how many transcriptions lost the tag', () async {
      final (repo, seen) = repoWith({
        'GET /api/tags': (_) => _json(libraryJson),
        'DELETE /api/tags/a': (_) => _json({'affected_transcriptions': 5}),
      });

      expect(await repo.deleteTag('meeting'), 5);
      expect(seen.last.url.path, '/api/tags/a');
    });

    test('a tag is put on a transcription by label, the gateway creates it if new', () async {
      final (repo, seen) = repoWith({'POST /api/transcriptions/t1/tags': (_) => _json(_tag('fresh', color: '#8F13AE'))});

      final tag = await repo.addTag('t1', 'fresh');

      expect(jsonDecode(seen.single.body), {'label': 'fresh'});
      expect(tag.color, const Color(0xFF8F13AE));
    });

    test('taking a tag off looks the id up and DELETEs it from that transcription only', () async {
      final (repo, seen) = repoWith({
        'GET /api/tags': (_) => _json(libraryJson),
        'DELETE /api/transcriptions/t1/tags/a': (_) => http.Response('', 204),
      });

      await repo.removeTag('t1', 'meeting');

      expect(seen.map((r) => '${r.method} ${r.url.path}'), ['GET /api/tags', 'DELETE /api/transcriptions/t1/tags/a']);
    });

    test('list entries carry the colors of their tags', () async {
      final (repo, _) = repoWith({
        'GET /api/transcriptions': (_) => _json({
              'items': [
                {
                  ..._listJson[0],
                  'tags': [_tag('meeting', color: '#8F13AE')],
                },
              ],
              'page': 0,
              'size': 30,
              'has_next': false,
            }),
      });

      final page = await repo.list(const TranscriptionQuery());

      expect(page.items.single.tags.single.color, const Color(0xFF8F13AE));
    });
  });

  group('speakers', () {
    const libraryJson = <String, dynamic>{
      'speakers': [
        {
          'id': 'a',
          'name': 'Maria Chen',
          'color': '#8F13AE',
          'description': 'Head of Engineering',
          'transcription_count': 5,
          'speaking_s': 3725.5,
          'sample_titles': ['New one', 'Older one'],
          'samples': [
            {'job_id': 'job-new', 'title': 'New one'},
            {'job_id': 'job-old', 'title': 'Older one'},
          ],
        },
        {'id': 'b', 'name': 'Unused', 'color': '#059669', 'transcription_count': 0, 'speaking_s': 0.0, 'sample_titles': <String>[], 'samples': <Map<String, String>>[]},
      ],
    };

    (HttpTranscriptionRepository, List<http.Request>) repoWith(Map<String, http.Response Function(http.Request)> routes) {
      final seen = <http.Request>[];
      final repo = _repo(MockClient((request) async {
        seen.add(request);
        final route = routes['${request.method} ${request.url.path}'];
        return route == null ? http.Response('no route', 500) : route(request);
      }));
      return (repo, seen);
    }

    test('the library comes from /speakers: description, count, speaking time, latest titles, colors', () async {
      final (repo, seen) = repoWith({'GET /api/speakers': (_) => _json(libraryJson)});

      final library = await repo.speakerLibrary();

      expect(seen.single.url.toString(), '/api/speakers');
      final maria = library.first;
      expect(maria.speaker.name, 'Maria Chen');
      expect(maria.speaker.color, const Color(0xFF8F13AE));
      expect(maria.description, 'Head of Engineering');
      expect(maria.count, 5);
      expect(maria.transcriptions.map((t) => (t.id, t.title)), [('job-new', 'New one'), ('job-old', 'Older one')]); // the latest two: count says the rest
      expect(maria.totalDuration, const Duration(hours: 1, minutes: 2, seconds: 5, milliseconds: 500));
      final unused = library.last;
      expect(unused.description, '');
      expect(unused.count, 0);
      expect(unused.totalDuration, Duration.zero);
    });

    test('speakers() are the library entries, for the picker', () async {
      final (repo, _) = repoWith({'GET /api/speakers': (_) => _json(libraryJson)});

      expect((await repo.speakers()).map((s) => s.name), ['Maria Chen', 'Unused']);
    });

    test('speakers() ask for the brief view, and read it: only id, name, color and description', () async {
      final (repo, seen) = repoWith({
        'GET /api/speakers': (_) => _json({
          'speakers': [
            {'id': 'a', 'name': 'Maria Chen', 'color': '#8F13AE', 'description': 'Head of Engineering'},
            {'id': 'b', 'name': 'Unused', 'color': '#059669'},
          ],
        }),
      });

      final speakers = await repo.speakers();

      expect(seen.single.url.queryParameters, {'view': 'brief'});
      expect(speakers.map((s) => s.name), ['Maria Chen', 'Unused']);
      expect(speakers.first.color, const Color(0xFF8F13AE));
    });

    test('the speaker library page still asks for the full list', () async {
      final (repo, seen) = repoWith({'GET /api/speakers': (_) => _json(libraryJson)});

      await repo.speakerLibrary();

      expect(seen.single.url.queryParameters.containsKey('view'), isFalse);
    });

    test('an answer that is not the library is an error', () async {
      final (repo, _) = repoWith({'GET /api/speakers': (_) => _json(['oops'])});

      await expectLater(repo.speakers(), throwsA(isA<GatewayException>()));
      await expectLater(repo.speakerLibrary(), throwsA(isA<GatewayException>()));
    });

    test('a speaker is created with POST /speakers and keeps the case', () async {
      final (repo, seen) = repoWith({
        'POST /api/speakers': (_) => _json({'id': 'x', 'name': 'Ann Lee', 'color': '#2563EB'}, status: 201),
      });

      final speaker = await repo.createSpeaker('Ann Lee', 'Designer');

      expect(jsonDecode(seen.single.body), {'name': 'Ann Lee', 'description': 'Designer'});
      expect(speaker.name, 'Ann Lee');
      expect(speaker.color, const Color(0xFF2563EB));
    });

    test('a taken name comes back as the gateways own message', () async {
      final (repo, _) = repoWith({
        'POST /api/speakers': (_) => _json({'message': 'A speaker with this name already exists'}, status: 409),
      });

      await expectLater(
        repo.createSpeaker('maria chen', ''),
        throwsA(isA<GatewayException>().having((e) => e.statusCode, 'statusCode', 409)),
      );
    });

    test('editing looks the id up by name and PUTs the new name and description', () async {
      final (repo, seen) = repoWith({
        'GET /api/speakers': (_) => _json(libraryJson),
        'PUT /api/speakers/a': (_) => _json({'id': 'a', 'name': 'Masha', 'color': '#2563EB'}),
      });

      await repo.updateSpeaker('Maria Chen', newName: 'Masha', description: 'new text');

      expect(seen.map((r) => '${r.method} ${r.url.path}'), ['GET /api/speakers', 'PUT /api/speakers/a']);
      expect(jsonDecode(seen.last.body), {'name': 'Masha', 'description': 'new text'});
    });

    test('editing or deleting a speaker that no longer exists does nothing', () async {
      final (repo, seen) = repoWith({'GET /api/speakers': (_) => _json(libraryJson)});

      await repo.updateSpeaker('Gone', newName: 'x', description: '');
      expect(await repo.deleteSpeaker('Gone'), 0);

      expect(seen.map((r) => r.method).toSet(), {'GET'});
    });

    test('deleting returns how many transcriptions lost the speaker', () async {
      final (repo, seen) = repoWith({
        'GET /api/speakers': (_) => _json(libraryJson),
        'DELETE /api/speakers/a': (_) => _json({'affected_transcriptions': 5}),
      });

      expect(await repo.deleteSpeaker('Maria Chen'), 5);
      expect(seen.last.url.path, '/api/speakers/a');
    });
  });

  group('speaker assignment', () {
    test('a choice for one segment is sent as scope "segment" with the phrase numbers', () async {
      late http.Request seen;
      final repo = _repo(MockClient((request) async {
        seen = request;
        return _json({'id': 'x', 'name': 'Maria Chen', 'color': '#8F13AE'});
      }));

      final speaker = await repo.assignSpeaker('t1', {4, 1}, 'Maria Chen');

      expect(seen.method, 'PUT');
      expect(seen.url.path, '/api/transcriptions/t1/speakers');
      expect(jsonDecode(seen.body), {'scope': 'segment', 'segment_indexes': [1, 4], 'name': 'Maria Chen'});
      expect(speaker.name, 'Maria Chen');
      expect(speaker.color, const Color(0xFF8F13AE));
    });

    test('"all segments by" is sent as scope "all"', () async {
      late http.Request seen;
      final repo = _repo(MockClient((request) async {
        seen = request;
        return _json({'id': 'x', 'name': 'Maria', 'color': '#2563EB'});
      }));

      await repo.assignSpeaker('t1', {0, 3}, 'Maria', allBySpeaker: true);

      expect(jsonDecode(seen.body)['scope'], 'all');
    });

    test('a refusal says why', () async {
      final repo = _repo(MockClient((_) async => _json({'message': 'This transcript has no phrase 9'}, status: 400)));

      await expectLater(
        repo.assignSpeaker('t1', {9}, 'Maria'),
        throwsA(isA<GatewayException>().having((e) => e.message, 'message', contains('no phrase 9'))),
      );
    });

    test('the detail carries the named voices and the phrases set by hand', () async {
      final json = <String, dynamic>{
        'job_id': 't1',
        'file_name': 'a.mp3',
        'created_at': '2026-09-20T16:36:21Z',
        'status': 'done',
        'file_size_bytes': 1,
        'segments': [
          {'start': 0.0, 'end': 1.0, 'text': 'a', 'speaker': 'SPEAKER_00'},
          {'start': 1.0, 'end': 2.0, 'text': 'b', 'speaker': 'SPEAKER_01', 'assigned_speaker': {'id': 'j', 'name': 'James', 'color': '#2563EB'}},
          {'start': 2.0, 'end': 3.0, 'text': 'c', 'speaker': 'SPEAKER_01'},
        ],
        'voices': [
          {
            'voice': 'SPEAKER_00',
            'speaker': {'id': 'm', 'name': 'Maria', 'color': '#8F13AE'},
          },
        ],
      };

      final detail = (await _repo(MockClient((_) async => _json(json))).get('t1'))!;

      expect(detail.speakers.keys, [1]); // voice SPEAKER_00 is slot 1
      expect(detail.speakers[1]!.name, 'Maria');
      expect(detail.segmentSpeakers.keys, [1]); // the second phrase
      expect(detail.segmentSpeakers[1]!.name, 'James');
      expect([for (var i = 0; i < 3; i++) detail.speakerOfSegment(i)!.name], ['Maria', 'James', 'Speaker 2']);
    });

    test('without voices or marks nobody is named', () async {
      final json = <String, dynamic>{
        'job_id': 't1',
        'file_name': 'a.mp3',
        'created_at': '2026-09-20T16:36:21Z',
        'status': 'done',
        'file_size_bytes': 1,
        'segments': [
          {'start': 0.0, 'end': 1.0, 'text': 'a', 'speaker': 'SPEAKER_00'},
        ],
      };

      final detail = (await _repo(MockClient((_) async => _json(json))).get('t1'))!;

      expect(detail.speakers, isEmpty);
      expect(detail.segmentSpeakers, isEmpty);
      expect(detail.speakerOfSegment(0)!.name, 'Speaker 1');
    });
  });

  group('detail', () {
    const detailJson = <String, dynamic>{
      'job_id': '3f1c2f0e-0000-4000-8000-000000000003',
      'file_name': 'review.m4a',
      'title': 'Quarterly Review',
      'file_size_bytes': 13800000,
      'created_at': '2026-09-20T16:36:21.609254Z',
      'status': 'done',
      'audio_duration_s': 862.5,
      'language': 'en',
      'word_count': 1840,
      'summary': '**Key points**\n* one',
      'tags': [
        {'id': 'id-meeting', 'label': 'meeting', 'color': '#2563EB'},
      ],
      'segments': [
        {'start': 0.0, 'end': 30.5, 'text': 'Good morning.', 'speaker': 'SPEAKER_00'},
        {'start': 34.0, 'end': 70.0, 'text': 'Right.', 'speaker': 'SPEAKER_01'},
        {'start': 72.0, 'end': 80.0, 'text': 'Yes.', 'speaker': 'SPEAKER_00'},
      ],
    };

    test('is read from GET /transcriptions/{id}', () async {
      final requests = <Uri>[];
      final repo = _repo(MockClient((request) async {
        requests.add(request.url);
        return _json(detailJson);
      }));

      final detail = (await repo.get('3f1c2f0e-0000-4000-8000-000000000003'))!;

      expect(requests.single.path, '/api/transcriptions/3f1c2f0e-0000-4000-8000-000000000003');
      expect(detail.item.title, 'Quarterly Review');
      expect(detail.item.duration, const Duration(milliseconds: 862500));
      expect(detail.item.tags.map((t) => t.label), ['meeting']);
      expect(detail.originalFilename, 'review.m4a');
      expect(detail.fileSizeBytes, 13800000);
      expect(detail.language, 'EN');
      expect(detail.wordCount, 1840);
      expect(detail.summary, '**Key points**\n* one');
      expect(detail.segments.map((s) => (s.start, s.speakerSlot, s.text)), [
        (Duration.zero, 1, 'Good morning.'),
        (const Duration(seconds: 34), 2, 'Right.'),
        (const Duration(seconds: 72), 1, 'Yes.'),
      ]);
      expect(detail.item.speakerCount, 2);
    });

    test('the detail carries the recording time when the gateway knows it', () async {
      final with_ = await _repo(MockClient((_) async => _json({...detailJson, 'recorded_at': '2026-06-08T17:39:00Z'}))).get('x');
      final without = await _repo(MockClient((_) async => _json(detailJson))).get('x');

      expect(with_!.item.recordedAt!.toUtc(), DateTime.utc(2026, 6, 8, 17, 39));
      expect(without!.item.recordedAt, isNull);
      expect(without.item.uploadedAt.toUtc(), DateTime.utc(2026, 9, 20, 16, 36, 21, 609, 254));
    });

    test('without diarization the segments have no speaker and there are no speakers', () async {
      final json = {
        ...detailJson,
        'summary': null,
        'segments': [
          {'start': 0.0, 'end': 1.0, 'text': 'Hello'},
        ],
      }..remove('language');
      final detail = (await _repo(MockClient((_) async => _json(json))).get('x'))!;

      expect(detail.segments.single.speakerSlot, isNull);
      expect(detail.speakerOfSegment(0), isNull);
      expect(detail.item.speakerCount, 0);
      expect(detail.summary, isNull);
      expect(detail.language, isNull);
      expect(detail.runs.single.speaker, isNull);
    });

    test('a transcription that does not exist, or is not done yet, is null', () async {
      expect(await _repo(MockClient((_) async => _json({'message': 'nope'}, status: 404))).get('x'), isNull);
      expect(await _repo(MockClient((_) async => _json({...detailJson, 'status': 'processing'}))).get('x'), isNull);
    });

    test('other errors are not hidden', () async {
      await expectLater(
        _repo(MockClient((_) async => _json({'message': 'boom'}, status: 500))).get('x'),
        throwsA(isA<GatewayException>().having((e) => e.statusCode, 'statusCode', 500)),
      );
    });

    test('rename is a PATCH of the title', () async {
      late http.Request seen;
      final repo = _repo(MockClient((request) async {
        seen = request;
        return http.Response('', 204);
      }));

      await repo.rename('abc', 'New name');

      expect(seen.method, 'PATCH');
      expect(seen.url.path, '/api/jobs/abc/title');
      expect(jsonDecode(seen.body), {'title': 'New name'});
      expect(seen.headers['Content-Type'], startsWith('application/json'));
    });

    test('a rejected rename says why', () async {
      final repo = _repo(MockClient((_) async => _json({'message': 'Title must be at most 200 characters'}, status: 400)));

      await expectLater(
        repo.rename('abc', 'x'),
        throwsA(isA<GatewayException>().having((e) => e.message, 'message', contains('at most 200'))),
      );
    });

    test('the transcript text is read from GET /transcript, as UTF-8', () async {
      late Uri seen;
      final repo = _repo(MockClient((request) async {
        seen = request.url;
        // No charset: the http package would decode this as Latin-1 on its own.
        return http.Response.bytes(utf8.encode('[00:00:00.000 --> 00:00:01.000] Привет — hello'), 200,
            headers: {'content-type': 'text/plain'});
      }));

      expect(await repo.transcriptText('abc'), '[00:00:00.000 --> 00:00:01.000] Привет — hello');
      expect(seen.path, '/api/transcript');
      expect(seen.queryParameters, {'jobId': 'abc'});
    });

    test('no transcript text is null', () async {
      expect(await _repo(MockClient((_) async => http.Response('', 404))).transcriptText('abc'), isNull);
    });

  });

  group('upload', () {
    const memo = ChosenFile(name: 'team_call.mp3', sizeBytes: 5 * 1024 * 1024);

    /// Answers an upload with [status] and [body]; remembers what was sent.
    (HttpTranscriptionRepository, _FakeUploader) repoWith(UploadReply reply) {
      final uploader = _FakeUploader(reply);
      return (HttpTranscriptionRepository(GatewayClient(uploader: uploader)), uploader);
    }

    final accepted = UploadReply(
      202,
      jsonEncode({
        'duplicate': false,
        'item': {
          'job_id': 'new-1',
          'file_name': 'team_call.mp3',
          'title': 'team_call.mp3',
          'created_at': '2026-09-22T10:00:00Z',
          'status': 'pending',
          'tags': <String>[],
        },
      }),
    );

    test('the file and the options of the dialog go to POST /transcriptions as a form', () async {
      final (repo, uploader) = repoWith(accepted);

      final outcome = await repo.upload(const UploadRequest(file: memo));

      expect(uploader.url, '/api/transcriptions');
      expect(uploader.file, memo);
      // Defaults of the dialog: auto language, auto summary, voices told apart, however many there are.
      expect(uploader.fields, {'language': 'auto', 'summary': 'auto', 'diarize': 'true', 'model': 'accurate'});
      expect(outcome.item.id, 'new-1');
      expect(outcome.item.status, TranscriptionStatus.pending);
      expect(outcome.duplicate, isFalse);
    });

    test('each option is sent in the gateway\'s words', () async {
      final (repo, uploader) = repoWith(accepted);

      await repo.upload(const UploadRequest(
        file: memo,
        language: UploadLanguage.russian,
        summary: SummaryMode.concise,
        speakers: SpeakerCount.three,
      ));

      expect(uploader.fields, {
        'language': 'ru',
        'summary': 'concise',
        'diarize': 'true',
        'num_speakers': '3',
        'model': 'accurate',
      });
    });

    test('every summary mode has the gateways name', () async {
      final sent = <String>[];
      for (final mode in SummaryMode.values) {
        final (repo, uploader) = repoWith(accepted);
        await repo.upload(UploadRequest(file: memo, summary: mode));
        sent.add(uploader.fields['summary']!);
      }
      expect(sent, ['off', 'auto', 'brief', 'concise', 'full']);
    });

    test('speakers: Off skips the diarization, Auto lets it decide, 4+ asks for four', () async {
      Future<Map<String, String>> sentFor(SpeakerCount speakers) async {
        final (repo, uploader) = repoWith(accepted);
        await repo.upload(UploadRequest(file: memo, speakers: speakers));
        return uploader.fields;
      }

      expect((await sentFor(SpeakerCount.off))['diarize'], 'false');
      expect((await sentFor(SpeakerCount.off)).containsKey('num_speakers'), isFalse);
      expect((await sentFor(SpeakerCount.auto))['diarize'], 'true');
      expect((await sentFor(SpeakerCount.auto)).containsKey('num_speakers'), isFalse);
      expect((await sentFor(SpeakerCount.one))['num_speakers'], '1');
      expect((await sentFor(SpeakerCount.fourPlus))['num_speakers'], '4');
    });

    test('a file the gateway already had comes back as a duplicate, with the existing entry', () async {
      final (repo, _) = repoWith(UploadReply(
        202,
        jsonEncode({
          'duplicate': true,
          'item': {
            'job_id': 'old-7',
            'file_name': 'team_call.mp3',
            'title': 'Weekly call',
            'created_at': '2026-09-01T10:00:00Z',
            'status': 'done',
            'tags': <String>[],
          },
        }),
      ));

      final outcome = await repo.upload(const UploadRequest(file: memo));

      expect(outcome.duplicate, isTrue);
      expect(outcome.item.id, 'old-7');
      expect(outcome.item.title, 'Weekly call');
      expect(outcome.item.status, TranscriptionStatus.done);
    });

    test('progress and cancelling are passed on to the uploader', () async {
      final (repo, uploader) = repoWith(accepted);
      final progress = <double>[];
      final cancellation = UploadCancellation();

      await repo.upload(const UploadRequest(file: memo), onProgress: progress.add, cancellation: cancellation);

      expect(progress, [0.25, 1.0]); // what the fake uploader reports
      expect(uploader.cancellation, same(cancellation));
    });

    test('a refusal carries the gateways message and status, so it can be shown as it is', () async {
      final (repo, _) = repoWith(UploadReply(429, jsonEncode({'message': 'Monthly transcription limit reached (10/month). Resets on 1 October.'})));

      await expectLater(
        repo.upload(const UploadRequest(file: memo)),
        throwsA(isA<GatewayException>()
            .having((e) => e.statusCode, 'statusCode', 429)
            .having((e) => e.message, 'message', startsWith('Monthly transcription limit reached'))),
      );
    });

    test('a refusal without a JSON message falls back to the status', () async {
      final (repo, _) = repoWith(const UploadReply(413, '<html>Request Entity Too Large</html>'));

      await expectLater(
        repo.upload(const UploadRequest(file: memo)),
        throwsA(isA<GatewayException>().having((e) => e.message, 'message', 'HTTP 413')),
      );
    });

    test('a broken connection or a cancelled upload is a GatewayException without a status', () async {
      final uploader = _FakeUploader(accepted)..interrupt = 'Could not reach the server';
      final repo = HttpTranscriptionRepository(GatewayClient(uploader: uploader));

      await expectLater(
        repo.upload(const UploadRequest(file: memo)),
        throwsA(isA<GatewayException>()
            .having((e) => e.statusCode, 'statusCode', isNull)
            .having((e) => e.message, 'message', 'Could not reach the server')),
      );
    });

    test('an answer that is not the new entry is an error', () async {
      final (repo, _) = repoWith(UploadReply(202, jsonEncode({'oops': true})));

      await expectLater(repo.upload(const UploadRequest(file: memo)), throwsA(isA<GatewayException>()));
    });
  });

  group('AI suggestions', () {
    Map<String, dynamic> withSuggestions(Object? suggestions) => {
          'job_id': 't1',
          'file_name': 'call.mp3',
          'title': 'Call',
          'created_at': '2026-09-20T16:36:21Z',
          'status': 'done',
          'tags': <Map<String, String>>[],
          'segments': [
            {'start': 0.0, 'end': 5.0, 'text': "Hi, I'm Anna.", 'speaker': 'SPEAKER_00'},
            {'start': 5.0, 'end': 9.0, 'text': 'Hello.', 'speaker': 'SPEAKER_01'},
          ],
          'suggestions': ?suggestions,
        };

    test('the proposals of the detail are read: title, tags and speakers by slot', () async {
      final repo = _repo(MockClient((_) async => _json(withSuggestions({
            'title': 'Intro call',
            'tags': [_tag('meeting', color: '#059669')],
            'speakers': [
              {'voice': 'SPEAKER_01', 'name': 'Anna', 'color': '#8F13AE', 'evidence': "I'm Anna"},
              {'voice': 'SPEAKER_00', 'name': 'Boris Petrov', 'color': '#2563EB', 'speaker_id': 'sp-1'},
            ],
          }))));

      final s = (await repo.get('t1'))!.suggestions!;

      expect(s.title, 'Intro call');
      expect(s.tags.map((t) => (t.label, t.color)), [('meeting', const Color(0xFF059669))]);
      expect(s.speakers.map((slot, speaker) => MapEntry(slot, (speaker.name, speaker.color))), {
        2: ('Anna', const Color(0xFF8F13AE)),
        1: ('Boris Petrov', const Color(0xFF2563EB)),
      });
    });

    test('a detail without proposals, or with an empty object, has none', () async {
      expect((await _repo(MockClient((_) async => _json(withSuggestions(null)))).get('t1'))!.suggestions, isNull);
      final empty = {'tags': <Object>[], 'speakers': <Object>[]};
      expect((await _repo(MockClient((_) async => _json(withSuggestions(empty)))).get('t1'))!.suggestions, isNull);
    });

    test('each action is a POST to its own address; a slot is the diarizer voice, a label is escaped', () async {
      final seen = <http.Request>[];
      final repo = _repo(MockClient((request) async {
        seen.add(request);
        return _json({});
      }));

      await repo.acceptAllSuggestions('t1');
      await repo.dismissAllSuggestions('t1');
      await repo.acceptTagSuggestion('t1', 'встреча');
      await repo.rejectTagSuggestion('t1', 'idea');
      await repo.acceptSpeakerSuggestion('t1', 1);
      await repo.rejectSpeakerSuggestion('t1', 12);

      expect(seen.map((r) => r.method).toSet(), {'POST'});
      expect(seen.map((r) => r.url.path.replaceFirst('/api/transcriptions/t1/suggestions/', '')), [
        'accept-all',
        'dismiss-all',
        'tags/${Uri.encodeComponent('встреча')}/accept',
        'tags/idea/reject',
        'speakers/SPEAKER_00/accept',
        'speakers/SPEAKER_11/reject',
      ]);
    });

    test('a refusal says why', () async {
      final repo = _repo(MockClient((_) async => _json({'message': 'No such suggestion'}, status: 404)));

      await expectLater(
        repo.acceptTagSuggestion('t1', 'idea'),
        throwsA(isA<GatewayException>().having((e) => e.message, 'message', 'No such suggestion')),
      );
    });
  });

  group('regenerate summary', () {
    Map<String, dynamic> detailWith(Object? regeneration) => {
          'job_id': 't1',
          'file_name': 'call.mp3',
          'title': 'Call',
          'created_at': '2026-09-20T16:36:21Z',
          'status': 'done',
          'tags': <Map<String, String>>[],
          'segments': <Object>[],
          'summary_regeneration': ?regeneration,
        };

    test("it is a POST to its own address, and the gateway's refusal comes through", () async {
      final seen = <http.Request>[];
      await _repo(MockClient((request) async {
        seen.add(request);
        return _json({'state': 'running'}, status: 202);
      })).regenerateSummary('t 1');

      expect(seen.single.method, 'POST');
      expect(seen.single.url.path, '/api/transcriptions/t%201/regenerate-summary');

      await expectLater(
        _repo(MockClient((_) async => _json({'message': 'The summary was already regenerated'}, status: 409))).regenerateSummary('t1'),
        throwsA(isA<GatewayException>().having((e) => e.statusCode, 'statusCode', 409)),
      );
    });

    test("deleting is a DELETE to the transcription's own address, and a refusal comes through with its words", () async {
      final seen = <http.Request>[];
      await _repo(MockClient((request) async {
        seen.add(request);
        return http.Response('', 204);
      })).delete('t 1');

      expect(seen.single.method, 'DELETE');
      expect(seen.single.url.path, '/api/transcriptions/t%201');

      await expectLater(
        _repo(MockClient((_) async => _json({'message': 'This transcription is still being processed. Try again when it is done.'}, status: 409))).delete('t1'),
        throwsA(isA<GatewayException>()
            .having((e) => e.statusCode, 'statusCode', 409)
            .having((e) => e.message, 'message', contains('still being processed'))),
      );
    });

    test('the detail tells the state, and the day it was used', () async {
      Future<TranscriptionDetail> read(Object? regeneration) async =>
          (await _repo(MockClient((_) async => _json(detailWith(regeneration)))).get('t1'))!;

      expect((await read({'state': 'available'})).regeneration, SummaryRegeneration.available);
      expect((await read({'state': 'running'})).regeneration, SummaryRegeneration.running);
      final used = await read({'state': 'used', 'at': '2026-10-15T10:00:00Z'});
      expect(used.regeneration, SummaryRegeneration.used);
      expect(used.regeneratedAt!.toUtc(), DateTime.utc(2026, 10, 15, 10));
      expect((await read({'state': 'unavailable'})).regeneration, SummaryRegeneration.unavailable);

      // How many times so far, and how many the plan allows (none given: no limit).
      final limited = await read({'state': 'available', 'used': 1, 'max': 3, 'at': '2026-10-15T10:00:00Z'});
      expect((limited.regenerationsUsed, limited.regenerationsMax, limited.regenerationsLeft), (1, 3, 2));
      final unlimited = await read({'state': 'available', 'used': 4});
      expect((unlimited.regenerationsUsed, unlimited.regenerationsMax, unlimited.regenerationsLeft), (4, null, null));
      expect((await read({'state': 'used', 'used': 1, 'max': 1})).regenerationsLeft, 0);

      // Why the last attempt failed, when it did.
      final failed = await read({'state': 'available', 'used': 0, 'max': 1, 'error': 'The summary service has run out of credits.'});
      expect(failed.regenerationError, 'The summary service has run out of credits.');
      expect((await read({'state': 'available', 'used': 0, 'max': 1})).regenerationError, isNull);
      // An older gateway says nothing: no button.
      expect((await read(null)).regeneration, SummaryRegeneration.unavailable);
    });
  });

  group('audioUrl', () {
    test('a token becomes a streaming url under /api', () async {
      final repo = _repo(MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/api/transcriptions/abc/audio-token');
        return _json({'token': 'tok en', 'expiresInSeconds': 2700});
      }));

      expect(await repo.audioUrl('abc'), '/api/transcriptions/abc/audio?token=tok+en');
    });

    test('no audio on record is null', () async {
      final repo = _repo(MockClient((_) async => _json({'message': 'No audio'}, status: 404)));

      expect(await repo.audioUrl('abc'), isNull);
    });

    test('a file that is gone from storage is told apart from no audio (410)', () async {
      final repo = _repo(MockClient((_) async => _json({'message': 'Audio is no longer available'}, status: 410)));

      expect(repo.audioUrl('abc'), throwsA(isA<AudioUnavailableException>()));
    });

    test('any other failure is still an error', () async {
      final repo = _repo(MockClient((_) async => _json({'message': 'boom'}, status: 500)));

      expect(repo.audioUrl('abc'), throwsA(isA<GatewayException>()));
    });
  });
}

/// `count` transcriptions in the shape of the gateway's list, newest first.
List<Map<String, dynamic>> _many(int count) => [
  for (var i = 0; i < count; i++)
    {
      'job_id': 'id-$i',
      'file_name': 'file$i.mp3',
      'title': 'File ${i.toString().padLeft(3, '0')}',
      'created_at': DateTime.utc(2026, 1, 1).subtract(Duration(minutes: i)).toIso8601String(),
      'status': 'done',
      'tags': i.isEven ? [_tag('even')] : <Map<String, String>>[],
    },
];

void _pagingTests() {
  group('HttpTranscriptionRepository paging', () {
    test('the default list is asked for from the gateway one page at a time', () async {
      final requests = <Uri>[];
      final repo = _repo(_gateway(_many(70), requests: requests));

      final first = await repo.list(const TranscriptionQuery());

      expect(requests.single.toString(), '/api/transcriptions?page=0&size=30');
      expect(first.items.length, 30);
      expect(first.items.first.id, 'id-0');
      expect(first.hasNext, isTrue);
    });

    test('page and size are passed on, and the last page says there is no more', () async {
      final requests = <Uri>[];
      final repo = _repo(_gateway(_many(70), requests: requests));

      final last = await repo.list(const TranscriptionQuery(), page: 2, size: 30);

      expect(requests.single.queryParameters, {'page': '2', 'size': '30'});
      expect(last.items.map((i) => i.id), [for (var i = 60; i < 70; i++) 'id-$i']);
      expect(last.hasNext, isFalse);
    });

    test('a refresh asks for page 0 with a bigger size', () async {
      final requests = <Uri>[];
      final repo = _repo(_gateway(_many(70), requests: requests));

      final refreshed = await repo.list(const TranscriptionQuery(), size: 60);

      expect(requests.single.queryParameters, {'page': '0', 'size': '60'});
      expect(refreshed.items.length, 60);
      expect(refreshed.hasNext, isTrue);
    });

    test('an answer without items is an error', () async {
      final repo = _repo(MockClient((request) async => _json({'page': 0})));
      await expectLater(repo.list(const TranscriptionQuery()), throwsA(isA<GatewayException>()));
    });

  });
}

void _fieldTests() {
  group('HttpTranscriptionRepository list fields', () {
    Map<String, dynamic> entry(Map<String, dynamic> extra) => {
          'job_id': 'x',
          'file_name': 'a.mp3',
          'title': 'a',
          'created_at': '2026-01-01T00:00:00Z',
          'status': 'done',
          'tags': <String>[],
          ...extra,
        };

    Future<Transcription> one(Map<String, dynamic> extra) async =>
        (await _repo(_gateway([entry(extra)])).list(const TranscriptionQuery())).items.single;

    test('the audio length in seconds becomes a duration', () async {
      expect((await one({'audio_duration_s': 862.0})).duration, const Duration(minutes: 14, seconds: 22));
      expect((await one({'audio_duration_s': 12})).duration, const Duration(seconds: 12)); // an integer in JSON
      expect((await one({'audio_duration_s': 0.4996})).duration, const Duration(milliseconds: 500));
    });

    test('without the fields there is no duration, no speakers and no progress', () async {
      final item = await one({});

      expect(item.duration, isNull);
      expect(item.speakerCount, 0);
      expect(item.processingPct, isNull);
      expect(item.processingPhase, isNull);
    });

    test('the number of speakers is read', () async {
      expect((await one({'speaker_count': 3})).speakerCount, 3);
      expect((await one({'speaker_count': 0})).speakerCount, 0);
    });

    test('progress and its phase are read', () async {
      final item = await one({'status': 'processing', 'processing_pct': 41, 'processing_phase': 'transcribing'});

      expect(item.processingPct, 41);
      expect(item.processingPhase, ProcessingPhase.transcribing);
    });

    test('each phase is known, and an unknown one is ignored', () async {
      expect((await one({'processing_phase': 'diarizing'})).processingPhase, ProcessingPhase.diarizing);
      expect((await one({'processing_phase': 'summarizing'})).processingPhase, ProcessingPhase.summarizing);
      expect((await one({'processing_phase': 'polishing'})).processingPhase, isNull);
    });
  });
}

void _queryTests() {
  group('HttpTranscriptionRepository list query', () {
    /// The URL a query is sent as.
    Future<Uri> urlFor(TranscriptionQuery query, {int page = 0, int size = 30}) async {
      final requests = <Uri>[];
      await _repo(_gateway(_many(3), requests: requests)).list(query, page: page, size: size);
      return requests.single;
    }

    test('a plain query sends only the page', () async {
      expect((await urlFor(const TranscriptionQuery())).toString(), '/api/transcriptions?page=0&size=30');
    });

    test('the search text is sent, trimmed', () async {
      expect((await urlFor(const TranscriptionQuery(search: '  review  '))).queryParameters['q'], 'review');
    });

    test('a blank search is not sent', () async {
      expect((await urlFor(const TranscriptionQuery(search: '   '))).queryParameters.containsKey('q'), isFalse);
    });

    test('search text with special characters arrives intact', () async {
      for (final text in ['100% & more', 'a+b', 'Разговор с клиентом', "it's", 'x=y?z#w', 'semi;colon']) {
        final url = await urlFor(TranscriptionQuery(search: text));
        expect(url.queryParameters['q'], text, reason: text);
      }
    });

    test('statuses are sent by their names, once each, in a fixed order', () async {
      final url = await urlFor(
        const TranscriptionQuery(statuses: {TranscriptionStatus.failed, TranscriptionStatus.done}),
      );

      expect(url.queryParametersAll['status'], ['done', 'failed']);
    });

    test('every status has the name the gateway knows', () async {
      final url = await urlFor(TranscriptionQuery(statuses: TranscriptionStatus.values.toSet()));
      expect(url.queryParametersAll['status'], ['done', 'processing', 'pending', 'failed']);
    });

    test('tags are sent as repeated parameters, sorted, and a comma stays in the name', () async {
      final url = await urlFor(const TranscriptionQuery(tags: {'standup', 'q3,planning', 'meeting'}));

      expect(url.queryParametersAll['tag'], ['meeting', 'q3,planning', 'standup']);
      expect(url.toString(), contains('tag=q3%2Cplanning'));
    });

    test('the default sort is not sent, the others are', () async {
      expect((await urlFor(const TranscriptionQuery())).queryParameters.containsKey('sort'), isFalse);
      expect(
        (await urlFor(const TranscriptionQuery(sort: TranscriptionSort.newest))).queryParameters.containsKey('sort'),
        isFalse,
      );
      for (final sort in [TranscriptionSort.oldest, TranscriptionSort.longest, TranscriptionSort.name]) {
        expect((await urlFor(TranscriptionQuery(sort: sort))).queryParameters['sort'], sort.name);
      }
    });

    test('the sorts by recording date go by the snake_case names of the gateway', () async {
      expect(
        (await urlFor(const TranscriptionQuery(sort: TranscriptionSort.recordedNewest))).queryParameters['sort'],
        'recorded_newest',
      );
      expect(
        (await urlFor(const TranscriptionQuery(sort: TranscriptionSort.recordedOldest))).queryParameters['sort'],
        'recorded_oldest',
      );
    });

    test('everything together, with the page', () async {
      final url = await urlFor(
        const TranscriptionQuery(
          search: 'team',
          statuses: {TranscriptionStatus.done},
          tags: {'standup'},
          sort: TranscriptionSort.longest,
        ),
        page: 2,
        size: 60,
      );

      expect(url.path, '/api/transcriptions');
      expect(url.queryParametersAll, {
        'page': ['2'],
        'size': ['60'],
        'q': ['team'],
        'status': ['done'],
        'tag': ['standup'],
        'sort': ['longest'],
      });
    });

    test('the gateway answer is used as it comes: no local filtering or sorting', () async {
      final repo = _repo(_gateway(_many(5)));

      final page = await repo.list(const TranscriptionQuery(sort: TranscriptionSort.name, search: 'x'));

      expect(page.items.map((i) => i.id), ['id-0', 'id-1', 'id-2', 'id-3', 'id-4']);
    });
  });
}

/// A stand-in for the browser's uploader: answers with [reply], remembers what it was given.
class _FakeUploader implements MultipartUploader {
  _FakeUploader(this.reply);

  final UploadReply reply;

  /// When set, the request never gets an answer.
  String? interrupt;

  String? url;
  Map<String, String> fields = const {};
  ChosenFile? file;
  Map<String, String> headers = const {};
  UploadCancellation? cancellation;

  @override
  Future<UploadReply> post(
    String url, {
    required Map<String, String> fields,
    required ChosenFile file,
    Map<String, String> headers = const {},
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  }) async {
    this.url = url;
    this.fields = fields;
    this.file = file;
    this.headers = headers;
    this.cancellation = cancellation;
    onProgress?.call(0.25);
    onProgress?.call(1);
    if (interrupt != null) throw UploadInterrupted(interrupt!);
    return reply;
  }
}
