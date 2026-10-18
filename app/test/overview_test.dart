import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'support/fake_transcription_repository.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/util/format.dart';

import 'test_utils.dart';

const _filename = 'product_sync_2025_06_23.mp4';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('formatting', () {
    test('timestamps and durations', () {
      expect(formatTimestamp(const Duration(seconds: 34)), '0:34');
      expect(formatTimestamp(const Duration(minutes: 14, seconds: 22)), '14:22');
      expect(formatTimestamp(const Duration(hours: 1, minutes: 2, seconds: 3)), '1:02:03');
      expect(formatDuration(const Duration(minutes: 14, seconds: 22)), '14 min');
      expect(formatDuration(const Duration(minutes: 72)), '1h 12min');
      expect(formatDuration(const Duration(seconds: 45)), '<1 min');
      expect(formatDuration(const Duration(minutes: 1)), '1 min');
      expect(formatDuration(Duration.zero), '0 min');
    });

    test('counts and file sizes', () {
      expect(formatCount(0), '0');
      expect(formatCount(999), '999');
      expect(formatCount(1840), '1,840');
      expect(formatCount(1234567), '1,234,567');
      expect(formatFileSize(512), '512 B');
      expect(formatFileSize(23488102), '22.4 MB');
      expect(formatFileSize(3 * 1024 * 1024 * 1024), '3.0 GB');
    });

    final now = DateTime(2025, 10, 18, 12);

    test('date with time', () {
      expect(formatDayTime(DateTime(2025, 6, 22, 14, 41), now: now), '22 Jun · 14:41');
      expect(formatDayTime(DateTime(2025, 1, 5, 9, 5), now: now), '5 Jan · 09:05');
    });

    test('the year is shown when it is not the current one, since recordings can be older than the upload', () {
      expect(formatDay(DateTime(2025, 3, 4), now: now), '4 Mar');
      expect(formatDay(DateTime(2024, 3, 4), now: now), '4 Mar 2024');
      expect(formatDay(DateTime(2026, 1, 1), now: now), '1 Jan 2026');
      expect(formatDayTime(DateTime(2024, 12, 31, 23, 59), now: now), '31 Dec 2024 · 23:59');
    });
  });

  group('Overview tab', () {
    Future<void> openOverview(WidgetTester tester, [String id = '0']) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository(), initialLocation: '/transcriptions/$id'));
      await settle(tester);
      await tester.tap(find.text('Overview'));
      await settle(tester);
    }

    testWidgets('shows the summary as rendered Markdown', (tester) async {
      await openOverview(tester);

      expect(find.text('SUMMARY'), findsOneWidget);
      expect(find.textContaining('Q2 closed strong'), findsOneWidget);
      // The bold headings and the bullets are drawn, not shown as `**...**` and `* ...`.
      expect(find.text('Key points'), findsOneWidget);
      expect(find.text('Action items'), findsOneWidget);
      expect(find.textContaining('Revenue up 18% QoQ'), findsOneWidget);
      expect(find.textContaining('Sales team to co-present'), findsOneWidget);
      expect(find.textContaining('**'), findsNothing);
      expect(find.text('•'), findsNWidgets(7));
    });

    testWidgets('shows the details panel', (tester) async {
      await openOverview(tester);

      expect(find.text('DETAILS'), findsOneWidget);
      // Value of each chip, next to its label.
      const facts = {
        'ORIGINAL FILE': _filename,
        'DURATION': '14:22',
        'SPEAKERS': '3',
        'LANGUAGE': 'EN',
        'WORDS': '~1,840',
        'FILE SIZE': '22.4 MB',
        'RECORDED': '20 Jun · 09:12',
        'UPLOADED': '22 Jun · 14:41',
      };
      for (final entry in facts.entries) {
        expect(find.text(entry.key), findsOneWidget, reason: entry.key);
        expect(find.text(entry.value), findsOneWidget, reason: entry.value);
      }
    });

    testWidgets('the details have no Recorded chip when the file does not say when it was recorded', (tester) async {
      await openOverview(tester, '4'); // Product Ideas — only the upload time is known

      expect(find.text('UPLOADED'), findsOneWidget);
      expect(find.text('RECORDED'), findsNothing);
    });

    testWidgets('a recording from an earlier year shows its year, in the chip, the header and its tooltip', (tester) async {
      await openOverview(tester, '2'); // Interview — recorded in March of the year before

      final year = DateTime.now().year - 1;
      expect(find.text('4 Mar $year · 10:30'), findsOneWidget); // the Recorded chip
      expect(find.text('4 Mar $year'), findsOneWidget); // the header, which has the day only
      expect(find.byTooltip('Recorded 4 Mar $year · 10:30'), findsOneWidget);
      expect(find.byTooltip(RegExp(r'^Uploaded 21 Jun · 16:20$')), findsOneWidget); // this year: no year
    });

    testWidgets('the header shows when it was uploaded and when it was recorded, each with its full time on hover', (tester) async {
      await openOverview(tester); // Quarterly Review: uploaded 22 Jun, recorded 20 Jun

      expect(find.byTooltip('Uploaded 22 Jun · 14:41'), findsOneWidget);
      expect(find.byTooltip('Recorded 20 Jun · 09:12'), findsOneWidget);
      expect(find.byIcon(Icons.cloud_upload_outlined), findsWidgets);
      expect(find.byIcon(Icons.mic_none_rounded), findsWidgets);
    });

    testWidgets('the header of a transcription with no recording date has the upload date only', (tester) async {
      await openOverview(tester, '4');

      expect(find.byTooltip('Uploaded 20 Jun · 18:02'), findsOneWidget);
      expect(find.byTooltip(RegExp(r'^Recorded ')), findsNothing);
    });

    testWidgets('a transcription without speakers shows a dash', (tester) async {
      await openOverview(tester, '4'); // Product Ideas — no diarization

      expect(find.text('—'), findsOneWidget);
    });

    testWidgets('the count follows a reassignment made on the Transcript tab', (tester) async {
      const picker = ValueKey('speaker-picker');
      Finder inPicker(Finder f) => find.descendant(of: find.byKey(picker), matching: f);

      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository(), initialLocation: '/transcriptions/0'));
      await settle(tester);
      await tester.tap(find.text('Overview'));
      await settle(tester);
      expect(find.text('3'), findsOneWidget); // Alexei, Maria, James

      await tester.tap(find.text('Transcript'));
      await settle(tester);
      await tester.tap(find.text('ALEXEI SOROKIN').first);
      await settle(tester);
      await tester.tap(inPicker(find.textContaining('All segments by')));
      await settle(tester);
      await tester.tap(inPicker(find.text('James Wright')));
      await settle(tester); // voice 1 (Alexei) is entirely James's now: down to 2 distinct speakers

      await tester.tap(find.text('Overview'));
      await settle(tester);
      expect(find.text('2'), findsOneWidget);
    });

    testWidgets('hovering the file chip shows the full name, which stays selectable', (tester) async {
      await openOverview(tester);
      expect(find.text(_filename), findsOneWidget); // the chip itself

      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await mouse.addPointer(location: Offset.zero);
      addTearDown(mouse.removePointer);

      await mouse.moveTo(tester.getCenter(find.text(_filename)));
      await settle(tester);
      expect(find.byType(SelectableText), findsOneWidget);
      expect(find.text(_filename), findsNWidgets(2)); // chip + floating box

      // The box stays while the pointer is on it...
      await mouse.moveTo(tester.getCenter(find.byType(SelectableText)));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byType(SelectableText), findsOneWidget);

      // ...and goes away once the pointer leaves both.
      await mouse.moveTo(const Offset(600, 700));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byType(SelectableText), findsNothing);
    });

    testWidgets('the floating file name stays inside the window', (tester) async {
      tester.view.physicalSize = const Size(1254, 800); // narrow enough for the chip to sit near the edge
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository(), initialLocation: '/transcriptions/0'));
      await settle(tester);
      await tester.tap(find.text('Overview'));
      await settle(tester);

      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await mouse.addPointer(location: Offset.zero);
      addTearDown(mouse.removePointer);
      await mouse.moveTo(tester.getCenter(find.text(_filename)));
      await settle(tester);

      final box = tester.getRect(find.byType(SelectableText));
      expect(box.right, lessThanOrEqualTo(1254));
      expect(box.left, greaterThanOrEqualTo(0));
    });

    testWidgets('the details panel keeps its two-column layout', (tester) async {
      await openOverview(tester);

      // 224 px column, two chips per row, 8 px apart.
      final file = tester.getRect(find.text('ORIGINAL FILE'));
      final duration = tester.getRect(find.text('DURATION'));
      // Values are set in different sizes (12 vs 14 px), so the labels differ by a few px.
      expect(duration.top, closeTo(file.top, 4));
      expect(duration.left, greaterThan(file.left));
      final speakers = tester.getRect(find.text('SPEAKERS'));
      expect(speakers.top, greaterThan(file.top));
      expect(speakers.left, closeTo(file.left, 1));
    });
  });
}
