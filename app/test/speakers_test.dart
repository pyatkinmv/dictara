import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/fake_transcription_repository.dart';
import 'package:dictara_web/models/speaker.dart';
import 'package:dictara_web/models/tag.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/transcription_detail.dart';

import 'test_utils.dart';

const _picker = ValueKey('speaker-picker');

Transcription _item() => Transcription(
      id: '1',
      title: 'T',
      duration: const Duration(minutes: 1),
      uploadedAt: DateTime(2025, 6, 22),
      status: TranscriptionStatus.done,
    );

const _ann = Speaker('Ann', Color(0xFF111111));
const _bob = Speaker('Bob', Color(0xFF222222));

Segment _seg(int slot) => Segment(start: Duration.zero, speakerSlot: slot, text: 't$slot');

TranscriptionDetail _detail(List<int> slots, {Map<int, Speaker> segmentSpeakers = const {}}) {
  return TranscriptionDetail(
    item: _item(),
    segments: [for (final s in slots) _seg(s)],
    speakers: const {1: _ann, 2: _bob},
    segmentSpeakers: segmentSpeakers,
    originalFilename: 'a.mp3',
    fileSizeBytes: 1,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('Speaker.initials', () {
    test('takes the first letters of the words, at most two', () {
      expect(const Speaker('Maria Chen', Color(0x00000000)).initials, 'MC');
      expect(const Speaker('alexei sorokin jr', Color(0x00000000)).initials, 'AS');
      expect(const Speaker('Cher', Color(0x00000000)).initials, 'C');
      expect(const Speaker('  Ann   Lee ', Color(0x00000000)).initials, 'AL');
    });
  });

  group('TranscriptionDetail with per-segment speakers', () {
    test('a manual assignment wins over the slot mapping', () {
      final detail = _detail([1, 2, 1], segmentSpeakers: const {0: _bob});
      expect(detail.speakerOfSegment(0), _bob);
      expect(detail.speakerOfSegment(1), _bob);
      expect(detail.speakerOfSegment(2), _ann);
    });

    test('runs merge and split around assignments and remember their first index', () {
      final plain = _detail([1, 2, 1, 2]);
      expect(plain.runs.map((r) => r.firstIndex), [0, 1, 2, 3]);

      // Segment 0 → Bob: it merges with segment 1 (also Bob).
      final merged = _detail([1, 2, 1, 2], segmentSpeakers: const {0: _bob});
      expect(merged.runs.map((r) => (r.speaker?.name, r.firstIndex, r.segments.length)),
          [('Bob', 0, 2), ('Ann', 2, 1), ('Bob', 3, 1)]);
    });

    test('segmentsBySpeaker lists everything currently attributed to a name', () {
      final detail = _detail([1, 2, 1, 2], segmentSpeakers: const {1: _ann});
      expect(detail.segmentsBySpeaker('Ann'), {0, 1, 2});
      expect(detail.segmentsBySpeaker('Bob'), {3});
    });
  });

  group('FakeTranscriptionRepository speakers', () {
    test('the library starts with the three speakers of the design', () async {
      final repo = FakeTranscriptionRepository();
      expect((await repo.speakers()).map((s) => s.name), ['Alexei Sorokin', 'Maria Chen', 'James Wright']);
    });

    test('assigning an existing speaker matches the name case-insensitively', () async {
      final repo = FakeTranscriptionRepository();
      final speaker = await repo.assignSpeaker('0', {0, 3}, 'maria chen');

      expect(speaker.name, 'Maria Chen');
      expect(speaker.color, const Color(0xFF7C3AED)); // the library's own color
      expect((await repo.get('0'))!.segmentSpeakers.keys, {0, 3});
      expect((await repo.speakers()).length, 3);
    });

    test('a new speaker keeps the case it was typed in and gets the next palette color', () async {
      final repo = FakeTranscriptionRepository();
      final speaker = await repo.assignSpeaker('0', {1}, '  Dana Whitfield ');

      expect(speaker.name, 'Dana Whitfield');
      expect(speaker.color, libraryPalette[3 % libraryPalette.length]); // library had 3 speakers
      expect((await repo.speakers()).map((s) => s.name), contains('Dana Whitfield'));
    });

    test('assignments belong to one transcription', () async {
      final repo = FakeTranscriptionRepository();
      await repo.assignSpeaker('0', {0}, 'James Wright');
      expect((await repo.get('2'))!.segmentSpeakers, isEmpty);
    });
    test('"all by" settles a whole voice for the transcription and keeps no marks for it', () async {
      final repo = FakeTranscriptionRepository();
      await repo.assignSpeaker('0', {1}, 'James Wright'); // an old mark on a phrase of voice 2
      await repo.assignSpeaker('0', {1, 4, 6, 9}, 'Maria Chen', allBySpeaker: true); // voice 2 is Maria's whole

      final detail = (await repo.get('0'))!;
      expect(detail.speakers[2]!.name, 'Maria Chen');
      expect(detail.segmentSpeakers, isEmpty); // the mark on phrase 1 went with it
      expect([for (var i = 0; i < detail.segments.length; i++) detail.speakerOfSegment(i)!.name].sublist(0, 3),
          ['Alexei Sorokin', 'Maria Chen', 'James Wright']);
    });

    test('"all by" with only some phrases of a voice marks those phrases and leaves the voice', () async {
      final repo = FakeTranscriptionRepository();

      await repo.assignSpeaker('0', {0, 3}, 'James Wright', allBySpeaker: true); // voice 1 also has phrase 7

      final detail = (await repo.get('0'))!;
      expect(detail.speakers[1]!.name, 'Alexei Sorokin');
      expect(detail.segmentSpeakers.keys, {0, 3});
    });

    test('"all by" made of a whole voice and a lone phrase settles the voice and marks the phrase', () async {
      final repo = FakeTranscriptionRepository();
      await repo.assignSpeaker('0', {2}, 'Alexei Sorokin'); // a phrase of voice 3 is Alexei's
      // Everything under "Alexei Sorokin": voice 1 (0, 3, 7) and phrase 2.
      await repo.assignSpeaker('0', {0, 2, 3, 7}, 'Maria Chen', allBySpeaker: true);

      final detail = (await repo.get('0'))!;
      expect(detail.speakers[1]!.name, 'Maria Chen');
      expect(detail.segmentSpeakers.keys, {2});
      expect(detail.segmentSpeakers[2]!.name, 'Maria Chen');
    });

    test('marking every phrase of a voice one by one names the voice', () async {
      final repo = FakeTranscriptionRepository();

      await repo.assignSpeaker('0', {1}, 'James Wright'); // voice 2 has phrases 1, 4, 6 and 9
      await repo.assignSpeaker('0', {4, 6}, 'James Wright');
      expect((await repo.get('0'))!.speakers[2]!.name, 'Maria Chen'); // three of four: not yet

      await repo.assignSpeaker('0', {9}, 'James Wright');

      final detail = (await repo.get('0'))!;
      expect(detail.speakers[2]!.name, 'James Wright');
      expect(detail.segmentSpeakers, isEmpty);
    });

    test('a mark that only repeats what the voice already says is not kept', () async {
      final repo = FakeTranscriptionRepository();
      await repo.assignSpeaker('0', {0}, 'James Wright');
      expect((await repo.get('0'))!.segmentSpeakers.keys, {0});

      await repo.assignSpeaker('0', {0}, 'Alexei Sorokin'); // voice 1 is Alexei's anyway

      expect((await repo.get('0'))!.segmentSpeakers, isEmpty);
    });

  });

  group('Speakers row', () {
    Future<void> openDetail(WidgetTester tester, [String id = '0']) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(initialLocation: '/transcriptions/$id'));
      await settle(tester);
    }

    testWidgets('shows a chip per speaker slot', (tester) async {
      await openDetail(tester);
      expect(find.text('Speakers:'), findsOneWidget);
      for (final name in ['Alexei Sorokin', 'Maria Chen', 'James Wright']) {
        expect(find.text(name), findsOneWidget, reason: name);
      }
    });

    testWidgets('a transcription without diarization has no speakers row', (tester) async {
      await openDetail(tester, '4');
      expect(find.text('Speakers:'), findsNothing);
    });

    testWidgets('unmapped slots show as Speaker N', (tester) async {
      await openDetail(tester, '6');
      expect(find.text('Speakers:'), findsOneWidget);
      for (var slot = 1; slot <= 3; slot++) {
        expect(find.text('Speaker $slot'), findsOneWidget);
      }
    });
  });

  group('Speaker picker', () {
    // Tall enough that the lazy list builds every block, so counts are stable.
    Future<void> openDetail(WidgetTester tester, {Size size = const Size(1280, 2400)}) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const DictaraApp(initialLocation: '/transcriptions/0'));
      await settle(tester);
    }

    Finder inPicker(Finder f) => find.descendant(of: find.byKey(_picker), matching: f);

    Future<void> openPicker(WidgetTester tester, String header, {int at = 0}) async {
      await tester.tap(find.text(header).at(at));
      await settle(tester);
    }

    testWidgets('clicking a speaker name opens the library with initials and scope', (tester) async {
      await openDetail(tester);
      expect(find.byKey(_picker), findsNothing);

      await openPicker(tester, 'MARIA CHEN');

      expect(find.byKey(_picker), findsOneWidget);
      for (final entry in {'Alexei Sorokin': 'AS', 'Maria Chen': 'MC', 'James Wright': 'JW'}.entries) {
        expect(inPicker(find.text(entry.key)), findsOneWidget, reason: entry.key);
        expect(inPicker(find.text(entry.value)), findsOneWidget, reason: entry.value);
      }
      expect(inPicker(find.text('APPLY TO')), findsOneWidget);
      expect(inPicker(find.text('This segment only')), findsOneWidget);
      expect(inPicker(find.textContaining('All segments by Maria Chen')), findsOneWidget);
    });

    testWidgets('"This segment only" reassigns just the first segment of the block', (tester) async {
      await openDetail(tester);
      expect(find.text('ALEXEI SOROKIN'), findsNWidgets(3));

      await openPicker(tester, 'ALEXEI SOROKIN'); // block of segment 0
      await tester.tap(inPicker(find.text('James Wright')));
      await settle(tester);

      expect(find.byKey(_picker), findsNothing);
      expect(find.text('ALEXEI SOROKIN'), findsNWidgets(2)); // the other two blocks stay
      expect(find.textContaining('Good morning everyone'), findsOneWidget);
      // Header row follows the slot mapping, not manual assignments (as in the design).
      expect(find.text('Alexei Sorokin'), findsOneWidget);
    });

    testWidgets('"All segments by" reassigns everything attributed to that speaker', (tester) async {
      await openDetail(tester);

      await openPicker(tester, 'ALEXEI SOROKIN');
      await tester.tap(inPicker(find.textContaining('All segments by')));
      await settle(tester);
      await tester.tap(inPicker(find.text('Maria Chen')));
      await settle(tester);

      expect(find.text('ALEXEI SOROKIN'), findsNothing);
      // Segment 0 now merges with segment 1 (Maria too): 10 blocks → fewer.
      expect(find.text('MARIA CHEN'), findsWidgets);
    });

    testWidgets('"All segments by" a voice names it in the row of chips too', (tester) async {
      await openDetail(tester);
      expect(find.text('Alexei Sorokin'), findsOneWidget); // the chip

      await openPicker(tester, 'ALEXEI SOROKIN');
      await tester.tap(inPicker(find.textContaining('All segments by')));
      await settle(tester);
      await tester.tap(inPicker(find.text('James Wright')));
      await settle(tester);

      expect(find.text('ALEXEI SOROKIN'), findsNothing); // no block
      expect(find.text('Alexei Sorokin'), findsNothing); // and no chip: the voice is James now
      expect(find.text('James Wright'), findsNWidgets(2)); // the chips of voice 1 and voice 3
    });

    testWidgets('typing filters the library and offers to create a speaker', (tester) async {
      await openDetail(tester);
      await openPicker(tester, 'MARIA CHEN');

      await tester.enterText(find.byType(TextField), 'ma');
      await settle(tester);
      expect(inPicker(find.text('Maria Chen')), findsOneWidget);
      expect(inPicker(find.text('James Wright')), findsNothing);
      expect(find.text('Create "ma"'), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'zzz');
      await settle(tester);
      expect(find.text('No match'), findsOneWidget);
      expect(find.text('Create "zzz"'), findsOneWidget);

      // A name already in the library (any case) is not offered again.
      await tester.enterText(find.byType(TextField), 'MARIA CHEN');
      await settle(tester);
      expect(find.textContaining('Create'), findsNothing);
    });

    testWidgets('creating a speaker keeps its case, adds it to the library and applies it', (tester) async {
      await openDetail(tester);
      await openPicker(tester, 'JAMES WRIGHT');

      await tester.enterText(find.byType(TextField), 'Dana Whitfield');
      await settle(tester);
      await tester.tap(find.text('Create "Dana Whitfield"'));
      await settle(tester);

      expect(find.byKey(_picker), findsNothing);
      expect(find.text('DANA WHITFIELD'), findsOneWidget); // the block header

      // The new speaker is in the library now, with its initials.
      await openPicker(tester, 'MARIA CHEN');
      expect(inPicker(find.text('Dana Whitfield')), findsOneWidget);
      expect(inPicker(find.text('DW')), findsOneWidget);
    });

    testWidgets('Enter picks the speaker with that name or creates a new one', (tester) async {
      await openDetail(tester);

      await openPicker(tester, 'JAMES WRIGHT');
      await tester.enterText(find.byType(TextField), 'alexei sorokin');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await settle(tester);
      expect(find.byKey(_picker), findsNothing);
      // Segment 2 moved from James to Alexei and merged with segment 3 (also Alexei): James lost a block,
      // Alexei did not gain a header.
      expect(find.text('JAMES WRIGHT'), findsNWidgets(2));
      expect(find.text('ALEXEI SOROKIN'), findsNWidgets(3));

      await openPicker(tester, 'MARIA CHEN');
      await tester.enterText(find.byType(TextField), 'Pat Lee');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await settle(tester);
      expect(find.text('PAT LEE'), findsOneWidget);
    });

    testWidgets('Escape and tapping outside close it', (tester) async {
      await openDetail(tester);

      await openPicker(tester, 'MARIA CHEN');
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);
      expect(find.byKey(_picker), findsNothing);

      await openPicker(tester, 'MARIA CHEN');
      await tester.tapAt(const Offset(700, 120));
      await settle(tester);
      expect(find.byKey(_picker), findsNothing);
    });

    testWidgets('it opens with an empty search and "This segment only" selected', (tester) async {
      await openDetail(tester);
      await openPicker(tester, 'MARIA CHEN');
      await tester.enterText(find.byType(TextField), 'zzz');
      await tester.tap(inPicker(find.textContaining('All segments by')));
      await settle(tester);
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);

      await openPicker(tester, 'MARIA CHEN');
      expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, isEmpty);
      await tester.tap(inPicker(find.text('James Wright')));
      await settle(tester);
      // Only one segment moved, so the other Maria blocks are still there.
      expect(find.text('MARIA CHEN'), findsWidgets);
      expect(find.text('JAMES WRIGHT'), findsWidgets);
    });

    testWidgets('near the bottom of the window it opens above the name and stays on screen', (tester) async {
      await openDetail(tester, size: const Size(1280, 700));
      await tester.drag(find.byType(ListView), const Offset(0, -3000));
      await settle(tester);

      await openPicker(tester, 'MARIA CHEN', at: 0);
      // Whichever header was hit, the panel must fit inside the window.
      final box = tester.getRect(find.byKey(_picker));
      expect(box.top, greaterThanOrEqualTo(0));
      expect(box.bottom, lessThanOrEqualTo(700));
      expect(box.right, lessThanOrEqualTo(1280));
    });

    testWidgets('assignments survive leaving and reopening the transcription', (tester) async {
      await openDetail(tester);
      await openPicker(tester, 'ALEXEI SOROKIN');
      await tester.tap(inPicker(find.text('James Wright')));
      await settle(tester);
      expect(find.text('ALEXEI SOROKIN'), findsNWidgets(2));

      await tester.tap(find.text('Transcriptions').last); // breadcrumb
      await settle(tester);
      await tester.tap(find.text('Quarterly Review Meeting'));
      await settle(tester);

      expect(find.text('ALEXEI SOROKIN'), findsNWidgets(2));
    });
  });
}
