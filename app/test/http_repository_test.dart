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

  group('HttpTranscriptionRepository.tags', () {
    test('are the library, asked for from /tags, in the order the gateway sends them', () async {
      final requests = <Uri>[];
      final repo = _repo(MockClient((request) async {
        requests.add(request.url);
        return _json([
          {'id': 'a', 'label': 'meeting'},
          {'id': 'b', 'label': 'standup'},
          {'id': 'c', 'label': 'unused'},
        ]);
      }));

      final tags = await repo.tags();

      expect(requests.single.toString(), '/api/tags');
      expect(tags.map((t) => t.label), ['meeting', 'standup', 'unused']); // unused ones too
      expect(tags.first.color, colorForLabel('meeting'));
    });

    test('an answer that is not a list is an error', () async {
      final repo = _repo(MockClient((request) async => _json({'oops': true})));
      await expectLater(repo.tags(), throwsA(isA<GatewayException>()));
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
      'tags': ['meeting'],
      'summary': '**Key points**\n* one',
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

    test('the speaker library is empty until it is connected', () async {
      expect(await _repo(MockClient((_) async => _json([]))).speakers(), isEmpty);
    });
  });

  group('not connected yet', () {
    final repo = _repo(MockClient((request) async => _json([])));

    test('other calls say which iteration connects them', () async {
      await expectLater(repo.addTag('1', 'x'), throwsA(isA<NotIntegratedException>().having((e) => e.iteration, 'iteration', 4)));
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
        const NotIntegratedException('Uploading', 7).toString(),
        'Uploading is not connected to the gateway yet (integration iteration 7)',
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
