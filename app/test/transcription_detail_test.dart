import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'support/fake_transcription_repository.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/models/speaker.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/transcription_detail.dart';

import 'test_utils.dart';

Transcription _item() => Transcription(
      id: '1',
      title: 'Title',
      duration: const Duration(minutes: 5),
      uploadedAt: DateTime(2025, 6, 22),
      status: TranscriptionStatus.done,
    );

const _ann = Speaker('Ann', Color(0xFF111111));
const _bob = Speaker('Bob', Color(0xFF222222));

Segment _seg(int slot, String text) => Segment(start: Duration.zero, speakerSlot: slot, text: text);

TranscriptionDetail _detail(List<Segment> segments, {Map<int, Speaker> speakers = const {}}) {
  return TranscriptionDetail(
    item: _item(),
    segments: segments,
    speakers: speakers,
    originalFilename: 'a.mp3',
    fileSizeBytes: 1024,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('TranscriptionDetail.runs', () {
    test('consecutive segments by one speaker form one block', () {
      final detail = _detail(
        [_seg(1, 'a'), _seg(1, 'b'), _seg(2, 'c'), _seg(1, 'd')],
        speakers: const {1: _ann, 2: _bob},
      );
      final runs = detail.runs;
      expect(runs.map((r) => r.speaker?.name), ['Ann', 'Bob', 'Ann']);
      expect(runs.map((r) => r.segments.length), [2, 1, 1]);
    });

    test('two slots mapped to the same person merge into one block', () {
      final detail = _detail([_seg(1, 'a'), _seg(2, 'b')], speakers: const {1: _ann, 2: _ann});
      expect(detail.runs.length, 1);
    });

    test('an unmapped slot is shown as "Speaker N"', () {
      final detail = _detail([_seg(3, 'a')]);
      expect(detail.runs.single.speaker?.name, 'Speaker 3');
    });
  });

  group('Detail page', () {
    Future<void> openDetail(WidgetTester tester, String location) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository(), initialLocation: location));
      await settle(tester);
    }

    testWidgets('shows the header, tabs and the transcript', (tester) async {
      await openDetail(tester, '/transcriptions/0');

      // Breadcrumb, title and the topbar all carry the title.
      expect(find.text('Quarterly Review Meeting'), findsNWidgets(3));
      expect(find.text('Transcriptions'), findsNWidgets(2)); // sidebar + breadcrumb
      expect(find.text('14 min'), findsOneWidget);
      expect(find.text('22 Jun'), findsOneWidget);
      expect(find.text('Done'), findsOneWidget);
      expect(find.text('.txt'), findsOneWidget);
      expect(find.text('Copy'), findsOneWidget);
      expect(find.text('Transcript'), findsOneWidget);
      expect(find.text('Overview'), findsOneWidget);

      expect(find.text('ALEXEI SOROKIN'), findsWidgets);
      expect(find.text('MARIA CHEN'), findsWidgets);
      expect(find.text('JAMES WRIGHT'), findsWidgets);
      expect(find.text('0:34'), findsOneWidget);
      expect(find.textContaining('Good morning everyone'), findsOneWidget);
    });

    testWidgets('speakers without a confirmed name show as Speaker N', (tester) async {
      await openDetail(tester, '/transcriptions/6');

      expect(find.text('SPEAKER 1'), findsWidgets);
      expect(find.text('ALEXEI SOROKIN'), findsNothing);
    });

    testWidgets('the Overview tab switches and back', (tester) async {
      await openDetail(tester, '/transcriptions/0');

      await tester.tap(find.text('Overview'));
      await settle(tester);
      expect(find.text('SUMMARY'), findsOneWidget);
      expect(find.textContaining('Good morning everyone'), findsNothing);

      await tester.tap(find.text('Transcript'));
      await settle(tester);
      expect(find.textContaining('Good morning everyone'), findsOneWidget);
    });

    testWidgets('a transcription that is not done or does not exist is not found', (tester) async {
      await openDetail(tester, '/transcriptions/3'); // pending
      expect(find.text('Transcription not found'), findsOneWidget);
    });

    testWidgets('an address without a valid id is not found', (tester) async {
      await openDetail(tester, '/transcriptions/abc');
      expect(find.text('Transcription not found'), findsOneWidget);
    });

    testWidgets('the breadcrumb goes back to the list', (tester) async {
      await openDetail(tester, '/transcriptions/0');

      await tester.tap(find.text('Transcriptions').last);
      await settle(tester);
      expect(find.text('No matching transcriptions'), findsNothing);
      expect(find.text('Daily Standup 19 Jun'), findsOneWidget);
    });

    group('renaming', () {
      Future<void> startRename(WidgetTester tester) async {
        await tester.tap(find.byTooltip('Rename'));
        await settle(tester);
      }

      testWidgets('saves with the button and updates the list', (tester) async {
        await openDetail(tester, '/transcriptions/0');
        await startRename(tester);

        await tester.enterText(find.byType(TextField), 'Q2 review');
        await tester.tap(find.byTooltip('Save'));
        await settle(tester);

        // Breadcrumb, title and topbar.
        expect(find.text('Q2 review'), findsNWidgets(3));
        expect(find.byType(TextField), findsNothing);

        await tester.tap(find.text('Transcriptions').last);
        await settle(tester);
        expect(find.text('Q2 review'), findsOneWidget);
        expect(find.text('Quarterly Review Meeting'), findsNothing);
      });

      testWidgets('Enter saves', (tester) async {
        await openDetail(tester, '/transcriptions/0');
        await startRename(tester);

        await tester.enterText(find.byType(TextField), 'Renamed');
        await tester.testTextInput.receiveAction(TextInputAction.done);
        await settle(tester);

        expect(find.text('Renamed'), findsNWidgets(3));
      });

      testWidgets('Escape and the cancel button discard the edit', (tester) async {
        await openDetail(tester, '/transcriptions/0');
        await startRename(tester);
        await tester.enterText(find.byType(TextField), 'Discarded');
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await settle(tester);
        expect(find.byType(TextField), findsNothing);
        expect(find.text('Quarterly Review Meeting'), findsNWidgets(3));

        await startRename(tester);
        await tester.enterText(find.byType(TextField), 'Discarded');
        await tester.tap(find.byTooltip('Cancel'));
        await settle(tester);
        expect(find.text('Discarded'), findsNothing);
        expect(find.text('Quarterly Review Meeting'), findsNWidgets(3));
      });

      testWidgets('an empty title keeps the old one', (tester) async {
        await openDetail(tester, '/transcriptions/0');
        await startRename(tester);

        await tester.enterText(find.byType(TextField), '   ');
        await tester.tap(find.byTooltip('Save'));
        await settle(tester);

        expect(find.text('Quarterly Review Meeting'), findsNWidgets(3));
      });
    });
  });
}
