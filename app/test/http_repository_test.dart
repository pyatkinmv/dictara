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
const List<Map<String, dynamic>> _listJson = [
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

    test('newest first by default, and the other sort orders work', () async {
      Future<List<String>> titles(TranscriptionSort sort) async =>
          [for (final i in (await repo.list(TranscriptionQuery(sort: sort))).items) i.title];

      expect(await titles(TranscriptionSort.newest), ['Daily Standup', 'lecture.wav', 'Voice memo', 'queued.mp3']);
      expect(await titles(TranscriptionSort.oldest), ['queued.mp3', 'Voice memo', 'lecture.wav', 'Daily Standup']);
      expect(await titles(TranscriptionSort.name), ['Daily Standup', 'lecture.wav', 'queued.mp3', 'Voice memo']);
    });

    test('search, status and tag filters apply', () async {
      Future<List<String>> titles(TranscriptionQuery q) async => [for (final i in (await repo.list(q)).items) i.title];

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

/// `count` transcriptions in the shape of the gateway's list, newest first.
List<Map<String, dynamic>> _many(int count) => [
  for (var i = 0; i < count; i++)
    {
      'job_id': 'id-$i',
      'file_name': 'file$i.mp3',
      'title': 'File ${i.toString().padLeft(3, '0')}',
      'created_at': DateTime.utc(2026, 1, 1).subtract(Duration(minutes: i)).toIso8601String(),
      'status': 'done',
      'tags': i.isEven ? ['even'] : <String>[],
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

    test('a search is answered from the whole list, cut into pages here', () async {
      final requests = <Uri>[];
      final repo = _repo(_gateway(_many(70), requests: requests));

      final page = await repo.list(const TranscriptionQuery(search: 'file 0'), size: 25);

      // "File 000".."File 069" contain "file 0" for every number: 70 hits, 25 per page.
      expect(requests.single.toString(), '/api/transcriptions'); // the unpaged array
      expect(page.items.length, 25);
      expect(page.hasNext, isTrue);
      final third = await repo.list(const TranscriptionQuery(search: 'file 0'), page: 2, size: 25);
      expect(third.items.length, 20);
      expect(third.hasNext, isFalse);
    });

    test('filters and other sort orders page in the same way', () async {
      final repo = _repo(_gateway(_many(70)));

      final tagged = await repo.list(const TranscriptionQuery(tags: {'even'}), size: 20);
      expect(tagged.items.length, 20);
      expect(tagged.items.every((i) => i.tags.any((t) => t.label == 'even')), isTrue);
      expect(tagged.hasNext, isTrue); // 35 tagged

      final oldest = await repo.list(const TranscriptionQuery(sort: TranscriptionSort.oldest), size: 5);
      expect(oldest.items.first.id, 'id-69');
    });
  });
}
