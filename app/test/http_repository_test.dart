import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/data/http_transcription_repository.dart';
import 'package:dictara_web/data/transcription_repository.dart';
import 'package:dictara_web/models/tag.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/upload.dart';

/// What `GET /transcriptions` of the gateway returns (`snake_case`, newest first).
const _listJson = [
  {
    'job_id': '3f1c2f0e-0000-4000-8000-000000000003',
    'file_name': 'standup.mp3',
    'title': 'Daily Standup',
    'created_at': '2026-09-20T16:36:21.609254Z',
    'status': 'done',
    'tags': ['meeting', 'standup'],
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
    'tags': ['meeting'],
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

void main() {
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
    final repo = _repo(MockClient((request) async => _json(_listJson)));

    test('maps the gateway fields to the model', () async {
      final items = await repo.list(const TranscriptionQuery());

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

    test('statuses are mapped, summarizing counts as processing', () async {
      final byId = {for (final i in await repo.list(const TranscriptionQuery())) i.title: i.status};

      expect(byId['Daily Standup'], TranscriptionStatus.done);
      expect(byId['lecture.wav'], TranscriptionStatus.processing); // was "summarizing"
      expect(byId['Voice memo'], TranscriptionStatus.failed);
    });

    test('a blank title falls back to the file name', () async {
      final items = await repo.list(const TranscriptionQuery());
      expect(items.map((i) => i.title), contains('queued.mp3'));
    });

    test('an unknown status is treated as pending', () async {
      final odd = _repo(
        MockClient(
          (request) async => _json([
            {
              'job_id': 'x',
              'file_name': 'a.mp3',
              'title': 'a',
              'created_at': '2026-01-01T00:00:00Z',
              'status': 'something-new',
              'tags': <String>[],
            },
          ]),
        ),
      );
      expect((await odd.list(const TranscriptionQuery())).single.status, TranscriptionStatus.pending);
    });

    test('an empty list is fine', () async {
      final empty = _repo(MockClient((request) async => _json([])));
      expect(await empty.list(const TranscriptionQuery()), isEmpty);
    });

    test('newest first by default, and the other sort orders work', () async {
      Future<List<String>> titles(TranscriptionSort sort) async =>
          [for (final i in await repo.list(TranscriptionQuery(sort: sort))) i.title];

      expect(await titles(TranscriptionSort.newest), ['Daily Standup', 'lecture.wav', 'Voice memo', 'queued.mp3']);
      expect(await titles(TranscriptionSort.oldest), ['queued.mp3', 'Voice memo', 'lecture.wav', 'Daily Standup']);
      expect(await titles(TranscriptionSort.name), ['Daily Standup', 'lecture.wav', 'queued.mp3', 'Voice memo']);
    });

    test('search, status and tag filters apply', () async {
      Future<List<String>> titles(TranscriptionQuery q) async => [for (final i in await repo.list(q)) i.title];

      expect(await titles(const TranscriptionQuery(search: 'VOICE')), ['Voice memo']);
      expect(await titles(const TranscriptionQuery(statuses: {TranscriptionStatus.failed})), ['Voice memo']);
      expect(await titles(const TranscriptionQuery(tags: {'meeting'})), ['Daily Standup', 'Voice memo']);
      expect(
        await titles(const TranscriptionQuery(tags: {'meeting'}, statuses: {TranscriptionStatus.done})),
        ['Daily Standup'],
      );
    });

    test('an answer that is not a list is an error', () async {
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

  group('HttpTranscriptionRepository.tags', () {
    test('are the tags in use, once each, in order of appearance', () async {
      final repo = _repo(MockClient((request) async => _json(_listJson)));
      expect((await repo.tags()).map((t) => t.label), ['meeting', 'standup']);
    });

    test('a tag always gets the same color, whatever the case', () {
      expect(colorForLabel('meeting'), colorForLabel('meeting'));
      expect(colorForLabel('Meeting'), colorForLabel('meeting'));
      expect(libraryPalette, contains(colorForLabel('anything at all')));
      // Different names do not all collapse into one color.
      final colors = {for (final l in ['meeting', 'hiring', 'ideas', 'standup', 'interview', 'quarterly']) colorForLabel(l)};
      expect(colors.length, greaterThan(2));
    });
  });

  group('not connected yet', () {
    final repo = _repo(MockClient((request) async => _json([])));

    test('other calls say which iteration connects them', () async {
      await expectLater(
        repo.get('1'),
        throwsA(isA<NotIntegratedException>().having((e) => e.iteration, 'iteration', 3)),
      );
      await expectLater(repo.tagLibrary(), throwsA(isA<NotIntegratedException>().having((e) => e.iteration, 'iteration', 4)));
      await expectLater(repo.speakerLibrary(), throwsA(isA<NotIntegratedException>().having((e) => e.iteration, 'iteration', 5)));
      await expectLater(
        repo.upload(const UploadRequest(file: ChosenFile(name: 'a.mp3', sizeBytes: 1))),
        throwsA(isA<NotIntegratedException>().having((e) => e.iteration, 'iteration', 7)),
      );
      await expectLater(repo.acceptAllSuggestions('1'), throwsA(isA<NotIntegratedException>()));
    });

    test('the message names what is missing', () {
      expect(
        const NotIntegratedException('Renaming', 3).toString(),
        'Renaming is not connected to the gateway yet (integration iteration 3)',
      );
    });
  });
}
