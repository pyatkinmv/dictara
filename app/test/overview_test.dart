import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
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

    test('date with time', () {
      expect(formatDayTime(DateTime(2025, 6, 22, 14, 41)), '22 Jun · 14:41');
      expect(formatDayTime(DateTime(2025, 1, 5, 9, 5)), '5 Jan · 09:05');
    });
  });

  group('Overview tab', () {
    Future<void> openOverview(WidgetTester tester, [String id = '0']) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(initialLocation: '/transcriptions/$id'));
      await settle(tester);
      await tester.tap(find.text('Overview'));
      await settle(tester);
    }

    testWidgets('shows the summary, key points and action items', (tester) async {
      await openOverview(tester);

      for (final label in ['SUMMARY', 'KEY POINTS', 'ACTION ITEMS']) {
        expect(find.text(label), findsOneWidget);
      }
      expect(find.textContaining('Q2 closed strong'), findsOneWidget);
      expect(find.textContaining('Revenue up 18% QoQ'), findsOneWidget);
      expect(find.textContaining('Reserve 20% engineering capacity'), findsOneWidget);
      expect(find.textContaining('Sales team to co-present'), findsOneWidget);
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
        'UPLOADED': '22 Jun · 14:41',
      };
      for (final entry in facts.entries) {
        expect(find.text(entry.key), findsOneWidget, reason: entry.key);
        expect(find.text(entry.value), findsOneWidget, reason: entry.value);
      }
    });

    testWidgets('a transcription without speakers shows a dash', (tester) async {
      await openOverview(tester, '4'); // Product Ideas — no diarization

      expect(find.text('—'), findsOneWidget);
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
      await tester.pumpWidget(const DictaraApp(initialLocation: '/transcriptions/0'));
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
