import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/fake_transcription_repository.dart';
import 'package:dictara_web/data/transcription_repository.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/upload.dart';
import 'package:dictara_web/widgets/transcription_row.dart';

import 'test_utils.dart';

const _mb = 1024 * 1024;

const _memo = ChosenFile(name: 'team_call.mp3', sizeBytes: 22 * _mb);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('ChosenFile.problem', () {
    test('accepts the listed formats in any case', () {
      for (final name in [
        'a.mp3',
        'a.MP4',
        'a.m4a',
        'a.ogg',
        'a.Wav',
        'a.flac',
        'many.dots.in.name.mp3',
      ]) {
        expect(
          ChosenFile(name: name, sizeBytes: 1).problem,
          isNull,
          reason: name,
        );
      }
    });

    test('refuses other formats and files without an extension', () {
      for (final name in ['a.txt', 'a.mp3.exe', 'mp3', 'a.']) {
        expect(
          ChosenFile(name: name, sizeBytes: 1).problem,
          startsWith('Unsupported format'),
          reason: name,
        );
      }
    });

    test('refuses more than 400 MB, accepts exactly 400 MB', () {
      expect(
        const ChosenFile(name: 'a.mp3', sizeBytes: 400 * _mb).problem,
        isNull,
      );
      expect(
        const ChosenFile(name: 'a.mp3', sizeBytes: 400 * _mb + 1).problem,
        'This file is larger than 400 MB.',
      );
    });
  });

  group('FakeTranscriptionRepository.upload', () {
    test(
      'adds a pending item that is newest and named after the file',
      () async {
        final now = DateTime(2025, 7, 1, 9, 30);
        final repo = FakeTranscriptionRepository(clock: () => now);

        final item = await repo.upload(const UploadRequest(file: _memo));

        expect(item.id, '7');
        expect(item.title, 'team_call.mp3');
        expect(item.status, TranscriptionStatus.pending);
        expect(item.uploadedAt, now);
        expect(
          item.duration,
          const Duration(minutes: 22),
        ); // about a minute per megabyte
        final list = await repo.list(const TranscriptionQuery());
        expect(list.first.id, '7');
        expect(list.length, 8);
        expect(await repo.get('7'), isNull); // not done yet
      },
    );

    test('without timing an upload stays pending', () async {
      var now = DateTime(2025, 7, 1);
      final repo = FakeTranscriptionRepository(clock: () => now);
      await repo.upload(const UploadRequest(file: _memo));

      now = now.add(const Duration(hours: 1));
      expect(
        (await repo.list(const TranscriptionQuery())).first.status,
        TranscriptionStatus.pending,
      );
    });

    test('with timing it moves through processing to done', () async {
      var now = DateTime(2025, 7, 1);
      final repo = FakeTranscriptionRepository(
        jobTiming: const JobTiming(
          queue: Duration(seconds: 4),
          processing: Duration(seconds: 20),
        ),
        clock: () => now,
      );
      await repo.upload(
        const UploadRequest(file: _memo, speakers: SpeakerCount.three),
      );

      Future<Transcription> uploaded() async => (await repo.list(
        const TranscriptionQuery(),
      )).firstWhere((i) => i.id == '7');

      var item = await uploaded();
      expect(item.status, TranscriptionStatus.pending);

      now = DateTime(2025, 7, 1, 0, 0, 4);
      item = await uploaded();
      expect(item.status, TranscriptionStatus.processing);
      expect(item.processingPct, 0);

      now = DateTime(2025, 7, 1, 0, 0, 14);
      item = await uploaded();
      expect(item.processingPct, 50);

      now = DateTime(2025, 7, 1, 0, 0, 24);
      item = await uploaded();
      expect(item.status, TranscriptionStatus.done);
      expect(item.processingPct, isNull);
      expect(item.speakerCount, 3);

      final detail = await repo.get('7');
      expect(detail, isNotNull);
      // A new recording has nobody confirmed: every slot is still "Speaker N".
      expect(detail!.speakers, isEmpty);
    });

    test('an unknown speaker count becomes two once done', () async {
      var now = DateTime(2025, 7, 1);
      final repo = FakeTranscriptionRepository(
        jobTiming: const JobTiming(
          queue: Duration.zero,
          processing: Duration(seconds: 1),
        ),
        clock: () => now,
      );
      await repo.upload(const UploadRequest(file: _memo));
      now = now.add(const Duration(seconds: 5));

      expect((await repo.get('7'))!.item.speakerCount, 2);
    });

    test(
      'small files last at least a minute and huge ones at most three hours',
      () async {
        final repo = FakeTranscriptionRepository();
        final tiny = await repo.upload(
          const UploadRequest(file: ChosenFile(name: 'a.mp3', sizeBytes: 10)),
        );
        final huge = await repo.upload(
          const UploadRequest(
            file: ChosenFile(name: 'b.mp3', sizeBytes: 400 * _mb),
          ),
        );

        expect(tiny.duration, const Duration(minutes: 1));
        expect(huge.duration, const Duration(hours: 3));
      },
    );
  });

  group('Upload dialog', () {
    Future<void> openApp(
      WidgetTester tester, {
      FakeFileChooser? chooser,
      FakeTranscriptionRepository? repo,
      String location = '/transcriptions',
    }) async {
      useDesktopSize(tester);
      await tester.pumpWidget(
        DictaraApp(
          initialLocation: location,
          repository: repo,
          fileChooser: chooser ?? FakeFileChooser(),
        ),
      );
      await settle(tester);
    }

    Future<void> openDialog(WidgetTester tester) async {
      await tester.tap(find.text('Upload'));
      await settle(tester);
    }

    testWidgets('the dialog has a title, hints and defaults', (tester) async {
      await openApp(tester);
      await openDialog(tester);

      expect(find.text('Upload audio'), findsOneWidget);
      expect(find.text('Drop file here or click to browse'), findsOneWidget);
      expect(
        find.text('MP3, MP4, M4A, OGG, WAV, FLAC · max 400 MB'),
        findsOneWidget,
      );
      for (final label in ['LANGUAGE', 'SUMMARY', 'SPEAKERS']) {
        expect(find.text(label), findsOneWidget, reason: label);
      }
      expect(
        find.text('Auto-detect'),
        findsNWidgets(2),
      ); // language and speakers
      expect(find.text('Auto'), findsOneWidget); // summary
      expect(find.text('Cancel'), findsOneWidget);
      expect(find.text('Upload'), findsNWidgets(2)); // topbar + dialog button
    });

    testWidgets('Cancel, the cross and Escape close it without uploading', (
      tester,
    ) async {
      final repo = FakeTranscriptionRepository();
      await openApp(tester, repo: repo);

      await openDialog(tester);
      await tester.tap(find.text('Cancel'));
      await settle(tester);
      expect(find.text('Upload audio'), findsNothing);

      await openDialog(tester);
      await tester.tap(find.byIcon(Icons.close_rounded));
      await settle(tester);
      expect(find.text('Upload audio'), findsNothing);

      await openDialog(tester);
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);
      expect(find.text('Upload audio'), findsNothing);

      expect(find.byType(TranscriptionRow), findsNWidgets(7));
    });

    testWidgets('clicking the drop zone asks the chooser and shows the file', (
      tester,
    ) async {
      final chooser = FakeFileChooser(_memo);
      await openApp(tester, chooser: chooser);
      await openDialog(tester);

      await tester.tap(find.text('Drop file here or click to browse'));
      await settle(tester);

      expect(chooser.calls, 1);
      expect(find.text('team_call.mp3'), findsOneWidget);
      expect(
        find.text('22.0 MB · click to choose another file'),
        findsOneWidget,
      );
      expect(find.text('Drop file here or click to browse'), findsNothing);
    });

    testWidgets('cancelling the chooser leaves the dialog as it was', (
      tester,
    ) async {
      final chooser = FakeFileChooser(); // picks nothing
      await openApp(tester, chooser: chooser);
      await openDialog(tester);

      await tester.tap(find.text('Drop file here or click to browse'));
      await settle(tester);

      expect(chooser.calls, 1);
      expect(find.text('Drop file here or click to browse'), findsOneWidget);
    });

    testWidgets('a file can be replaced by choosing another', (tester) async {
      final chooser = FakeFileChooser(_memo);
      await openApp(tester, chooser: chooser);
      await openDialog(tester);
      await tester.tap(find.text('Drop file here or click to browse'));
      await settle(tester);

      chooser.next = const ChosenFile(name: 'other.wav', sizeBytes: 3 * _mb);
      await tester.tap(find.text('team_call.mp3'));
      await settle(tester);

      expect(find.text('other.wav'), findsOneWidget);
      expect(find.text('team_call.mp3'), findsNothing);
    });

    testWidgets('Upload does nothing until a file is chosen', (tester) async {
      await openApp(tester);
      await openDialog(tester);

      await tester.tap(find.text('Upload').last);
      await settle(tester);

      expect(find.text('Upload audio'), findsOneWidget);
    });

    testWidgets('an unsupported file shows why and cannot be uploaded', (
      tester,
    ) async {
      final chooser = FakeFileChooser(
        const ChosenFile(name: 'notes.txt', sizeBytes: 100),
      );
      await openApp(tester, chooser: chooser);
      await openDialog(tester);
      await tester.tap(find.text('Drop file here or click to browse'));
      await settle(tester);

      expect(find.textContaining('Unsupported format'), findsOneWidget);

      await tester.tap(find.text('Upload').last);
      await settle(tester);
      expect(find.text('Upload audio'), findsOneWidget);
      expect(find.byType(TranscriptionRow), findsNWidgets(7));

      // Picking a good one afterwards clears the complaint.
      chooser.next = _memo;
      await tester.tap(find.text('notes.txt'));
      await settle(tester);
      expect(find.textContaining('Unsupported format'), findsNothing);
    });

    testWidgets('a file over 400 MB is refused', (tester) async {
      final chooser = FakeFileChooser(
        const ChosenFile(name: 'big.mp4', sizeBytes: 401 * _mb),
      );
      await openApp(tester, chooser: chooser);
      await openDialog(tester);
      await tester.tap(find.text('Drop file here or click to browse'));
      await settle(tester);

      expect(find.text('This file is larger than 400 MB.'), findsOneWidget);
    });

    testWidgets(
      'uploading closes the dialog and the new item tops the list as pending',
      (tester) async {
        await openApp(tester, chooser: FakeFileChooser(_memo));
        await openDialog(tester);
        await tester.tap(find.text('Drop file here or click to browse'));
        await settle(tester);

        await tester.tap(find.text('Upload').last);
        await settle(tester);

        expect(find.text('Upload audio'), findsNothing);
        final rows = tester
            .widgetList<TranscriptionRow>(find.byType(TranscriptionRow))
            .toList();
        expect(rows.length, 8);
        expect(rows.first.item.title, 'team_call.mp3');
        expect(rows.first.item.status, TranscriptionStatus.pending);
      },
    );

    testWidgets(
      'a search or filter left on the list does not hide the new item',
      (tester) async {
        await openApp(tester, chooser: FakeFileChooser(_memo));
        await tester.enterText(find.byType(TextField), 'standup');
        await settle(tester);
        expect(find.byType(TranscriptionRow), findsOneWidget);

        await openDialog(tester);
        await tester.tap(find.text('Drop file here or click to browse'));
        await settle(tester);
        await tester.tap(find.text('Upload').last);
        await settle(tester);

        expect(find.byType(TranscriptionRow), findsNWidgets(8));
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          isEmpty,
        );
      },
    );

    testWidgets('uploading from another page leads to the list', (
      tester,
    ) async {
      await openApp(tester, chooser: FakeFileChooser(_memo), location: '/tags');
      expect(find.text('Add tag'), findsWidgets);

      await openDialog(tester);
      await tester.tap(find.text('Drop file here or click to browse'));
      await settle(tester);
      await tester.tap(find.text('Upload').last);
      await settle(tester);

      expect(find.text('Add tag'), findsNothing);
      expect(find.text('team_call.mp3'), findsOneWidget);
    });

    testWidgets('the chosen options travel with the request', (tester) async {
      final repo = _RecordingRepository();
      await openApp(tester, chooser: FakeFileChooser(_memo), repo: repo);
      await openDialog(tester);
      await tester.tap(find.text('Drop file here or click to browse'));
      await settle(tester);

      Future<void> choose(
        String current,
        String option, {
        int index = 0,
      }) async {
        await tester.tap(find.text(current).at(index));
        await settle(tester);
        await tester.tap(find.text(option).last);
        await settle(tester);
      }

      await choose('Auto-detect', 'Russian'); // language
      await choose('Auto', 'Short'); // summary
      await choose(
        'Auto-detect',
        '4+',
      ); // speakers: the language one no longer says so
      await tester.tap(find.text('Upload').last);
      await settle(tester);

      final request = repo.requests.single;
      expect(request.file, _memo);
      expect(request.language, UploadLanguage.russian);
      expect(request.summary, SummaryMode.short);
      expect(request.speakers, SpeakerCount.fourPlus);
    });
  });

  group('Progress polling', () {
    testWidgets(
      'the list follows an upload from pending to done without a reload',
      (tester) async {
        useDesktopSize(tester);
        var now = DateTime(2025, 7, 1);
        final repo = FakeTranscriptionRepository(
          jobTiming: const JobTiming(
            queue: Duration(seconds: 4),
            processing: Duration(seconds: 20),
          ),
          clock: () => now,
        );
        await tester.pumpWidget(
          DictaraApp(
            repository: repo,
            fileChooser: FakeFileChooser(_memo),
            pollInterval: const Duration(seconds: 2),
          ),
        );
        await settle(tester);

        await tester.tap(find.text('Upload'));
        await settle(tester);
        await tester.tap(find.text('Drop file here or click to browse'));
        await settle(tester);
        await tester.tap(find.text('Upload').last);
        await settle(tester);

        TranscriptionStatus statusOfFirst() => tester
            .widgetList<TranscriptionRow>(find.byType(TranscriptionRow))
            .first
            .item
            .status;
        expect(statusOfFirst(), TranscriptionStatus.pending);

        now = DateTime(2025, 7, 1, 0, 0, 10);
        await tester.pump(const Duration(seconds: 2));
        await tester.pump();
        expect(statusOfFirst(), TranscriptionStatus.processing);
        expect(find.textContaining('30%'), findsOneWidget);

        now = DateTime(2025, 7, 1, 0, 0, 30);
        await tester.pump(const Duration(seconds: 2));
        await tester.pump();
        expect(statusOfFirst(), TranscriptionStatus.done);

        await tester.pumpWidget(const SizedBox()); // stop the polling timer
      },
    );

    testWidgets('without a poll interval the list does not refresh by itself', (
      tester,
    ) async {
      useDesktopSize(tester);
      var now = DateTime(2025, 7, 1);
      final repo = FakeTranscriptionRepository(
        jobTiming: const JobTiming(
          queue: Duration.zero,
          processing: Duration(seconds: 1),
        ),
        clock: () => now,
      );
      await repo.upload(const UploadRequest(file: _memo));
      await tester.pumpWidget(DictaraApp(repository: repo));
      await settle(tester);
      expect(
        tester
            .widgetList<TranscriptionRow>(find.byType(TranscriptionRow))
            .first
            .item
            .title,
        'team_call.mp3',
      );

      now = now.add(const Duration(minutes: 1));
      await tester.pump(const Duration(seconds: 10));

      expect(
        tester
            .widgetList<TranscriptionRow>(find.byType(TranscriptionRow))
            .first
            .item
            .status,
        isNot(TranscriptionStatus.done),
      );
    });
  });
}

/// A repository that remembers what was uploaded.
class _RecordingRepository extends FakeTranscriptionRepository {
  final List<UploadRequest> requests = [];

  @override
  Future<Transcription> upload(UploadRequest request) {
    requests.add(request);
    return super.upload(request);
  }
}
