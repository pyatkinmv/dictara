import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'support/fake_transcription_repository.dart';
import 'package:dictara_web/data/transcription_repository.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/state/transcription_list_controller.dart';
import 'package:dictara_web/widgets/transcription_row.dart';

import 'test_utils.dart';

// Titles in the default ("newest first") order, as in the design.
const _newestFirst = [
  'product_sync_2025_06_23.mp4',
  'Daily Standup 19 Jun',
  'Product Ideas — Voice Memo',
  'Podcast Episode #47',
  'Interview: Anna K.',
  'Economics Lecture — Part 2',
  'Quarterly Review Meeting',
];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('FakeTranscriptionRepository', () {
    final repo = FakeTranscriptionRepository();

    Future<List<String>> titles(TranscriptionQuery q) async =>
        [for (final t in (await repo.list(q)).items) t.title];

    test('returns everything newest first by default', () async {
      expect(await titles(const TranscriptionQuery()), _newestFirst);
    });

    test('sorts oldest first, longest and by name', () async {
      expect(
        await titles(const TranscriptionQuery(sort: TranscriptionSort.oldest)),
        _newestFirst.reversed.toList(),
      );
      expect((await titles(const TranscriptionQuery(sort: TranscriptionSort.longest))).first,
          'Economics Lecture — Part 2');
      expect((await titles(const TranscriptionQuery(sort: TranscriptionSort.name))).first,
          'Daily Standup 19 Jun');
    });

    test('sorts by the recording date, and a recording with no date counts by its upload time', () async {
      const newestRecorded = [
        'product_sync_2025_06_23.mp4', // no date: uploaded 23 Jun
        'Economics Lecture — Part 2', // no date: uploaded 22 Jun 11:05
        'Podcast Episode #47', // no date: uploaded 22 Jun 09:30
        'Product Ideas — Voice Memo', // no date: uploaded 20 Jun 18:02
        'Quarterly Review Meeting', // recorded 20 Jun 09:12 (uploaded two days later)
        'Daily Standup 19 Jun', // no date: uploaded 19 Jun
        'Interview: Anna K.', // recorded the year before (uploaded in June)
      ];
      expect(await titles(const TranscriptionQuery(sort: TranscriptionSort.recordedNewest)), newestRecorded);
      expect(
        await titles(const TranscriptionQuery(sort: TranscriptionSort.recordedOldest)),
        newestRecorded.reversed.toList(),
      );
    });

    test('search is case-insensitive and matches the title', () async {
      expect(await titles(const TranscriptionQuery(search: 'STANDUP')), ['Daily Standup 19 Jun']);
    });

    test('status filter matches any selected status', () async {
      final q = const TranscriptionQuery(
        statuses: {TranscriptionStatus.pending, TranscriptionStatus.processing},
      );
      expect(await titles(q), ['Podcast Episode #47', 'Economics Lecture — Part 2']);
    });

    test('tag filter matches any selected tag', () async {
      final q = const TranscriptionQuery(tags: {'hiring', 'ideas'});
      expect(await titles(q), ['Product Ideas — Voice Memo', 'Interview: Anna K.']);
    });

    test('filters combine', () async {
      final q = const TranscriptionQuery(tags: {'meeting'}, search: 'review');
      expect(await titles(q), ['Quarterly Review Meeting']);
    });

    test('speaker filter matches any selected speaker, but never a still-queued item', () async {
      final q = const TranscriptionQuery(speakers: {'Alexei Sorokin'});
      // The 4 done items with a confirmed voice map; 'product_sync_2025_06_23.mp4' (done, but no speaker
      // confirmed yet) and the pending/processing items are never among them.
      expect(await titles(q), ['Daily Standup 19 Jun', 'Product Ideas — Voice Memo', 'Interview: Anna K.', 'Quarterly Review Meeting']);
    });

    test('a speaker nobody has been assigned to yet matches nothing', () async {
      expect(await titles(const TranscriptionQuery(speakers: {'Dana Whitfield'})), isEmpty);
    });

    test('a live reassignment changes what the filter matches, the same as the detail page', () async {
      final fresh = FakeTranscriptionRepository();
      Future<List<String>> titlesOf(TranscriptionQuery q) async => [for (final t in (await fresh.list(q)).items) t.title];
      // Settles voice 2 (Maria's, segments 1/4/6/9 of item '0') to a new name for that transcription only.
      await fresh.assignSpeaker('0', {1, 4, 6, 9}, 'Dana Whitfield', allBySpeaker: true);

      expect(await titlesOf(const TranscriptionQuery(speakers: {'Dana Whitfield'})), ['Quarterly Review Meeting']);
      expect(await titlesOf(const TranscriptionQuery(speakers: {'Maria Chen'})),
          ['Daily Standup 19 Jun', 'Product Ideas — Voice Memo', 'Interview: Anna K.']); // '0' no longer among them
    });
  });

  group('TranscriptionListController.filterByTag/filterBySpeaker', () {
    test('replaces the whole query with just that one tag, dropping every other filter', () async {
      final c = TranscriptionListController(FakeTranscriptionRepository());
      c.setSearch('review');
      c.toggleStatus(TranscriptionStatus.done);
      c.toggleSpeaker('Alexei Sorokin');
      await Future<void>.delayed(Duration.zero);

      c.filterByTag('ideas');
      await Future<void>.delayed(Duration.zero);

      expect(c.query.tags, {'ideas'});
      expect(c.query.search, isEmpty);
      expect(c.query.statuses, isEmpty);
      expect(c.query.speakers, isEmpty);
      expect(c.items.map((i) => i.title), ['Product Ideas — Voice Memo']);
    });

    test('the speaker counterpart does the same, the other way round', () async {
      final c = TranscriptionListController(FakeTranscriptionRepository());
      c.toggleTag('meeting');
      await Future<void>.delayed(Duration.zero);

      c.filterBySpeaker('James Wright');
      await Future<void>.delayed(Duration.zero);

      expect(c.query.speakers, {'James Wright'});
      expect(c.query.tags, isEmpty);
      expect(c.items, isNotEmpty);
      expect(c.items.every((i) => i.status == TranscriptionStatus.done), isTrue);
    });
  });

  group('TranscriptionListController.clearAll', () {
    test('resets the search text and every filter, leaving sort untouched', () async {
      final c = TranscriptionListController(FakeTranscriptionRepository());
      c.setSearch('standup');
      c.toggleStatus(TranscriptionStatus.done);
      c.toggleTag('meeting');
      c.toggleSpeaker('Alexei Sorokin');
      c.setSort(TranscriptionSort.oldest);
      await Future<void>.delayed(Duration.zero);

      c.clearAll();
      await Future<void>.delayed(Duration.zero);

      expect(c.query.search, isEmpty);
      expect(c.query.statuses, isEmpty);
      expect(c.query.tags, isEmpty);
      expect(c.query.speakers, isEmpty);
      expect(c.query.sort, TranscriptionSort.oldest); // sort is not a filter: left as it was
      expect(c.items.map((i) => i.title), _newestFirst.reversed);
    });
  });

  group('Transcriptions page', () {
    Future<void> open(WidgetTester tester) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
      await settle(tester);
    }

    List<String> visibleTitles(WidgetTester tester) {
      final rows = tester.widgetList<TranscriptionRow>(find.byType(TranscriptionRow));
      return [for (final r in rows) r.item.title];
    }

    testWidgets('lists every transcription in the design order', (tester) async {
      await open(tester);
      expect(visibleTitles(tester), _newestFirst);
      expect(find.text('41% — transcribing…'), findsOneWidget);
      expect(find.text('Processing'), findsOneWidget);
      expect(find.text('Pending'), findsOneWidget);
      expect(find.text('Done'), findsNWidgets(5));
    });

    testWidgets('search filters as you type', (tester) async {
      await open(tester);
      await tester.enterText(find.byType(TextField), 'standup');
      await settle(tester);
      expect(visibleTitles(tester), ['Daily Standup 19 Jun']);

      await tester.enterText(find.byType(TextField), 'zzz');
      await settle(tester);
      expect(find.text('No matching transcriptions'), findsOneWidget);
    });

    testWidgets('status filter updates the list and the button', (tester) async {
      await open(tester);
      await tester.tap(find.text('Filter'));
      await settle(tester);

      await tester.tap(find.widgetWithText(InkWell, 'Pending'));
      await settle(tester);

      expect(visibleTitles(tester), ['Podcast Episode #47']);
      expect(find.text('Filter · 1'), findsOneWidget);
    });

    testWidgets("'Clear all' stays put (disabled) until something is active, and resets search too", (tester) async {
      InkWell clearAllInkWell() =>
          tester.widget<InkWell>(find.ancestor(of: find.text('Clear all'), matching: find.byType(InkWell)));

      await open(tester);
      await tester.tap(find.text('Filter'));
      await settle(tester);
      // Always present (no layout jump when it later turns active), but not tappable yet.
      expect(find.text('Clear all'), findsOneWidget);
      expect(clearAllInkWell().onTap, isNull);

      await tester.tap(find.widgetWithText(InkWell, 'Pending'));
      await settle(tester);
      expect(clearAllInkWell().onTap, isNotNull); // a status filter counts too

      await tester.tap(find.text('Clear all'));
      await settle(tester);
      expect(clearAllInkWell().onTap, isNull); // nothing left to clear
      expect(find.text('Filter'), findsOneWidget); // back to the unfiltered label, no "· N"
      expect(visibleTitles(tester), _newestFirst);

      // The search field also counts, and clearing resets it too. The dropdown is still open from
      // above ("Clear all" doesn't close it) — that's also why 'main-search' is needed here: with it
      // open there are three TextFields (search plus the tag/speaker search boxes).
      await tester.enterText(find.byKey(const Key('main-search')), 'standup');
      await settle(tester);
      expect(clearAllInkWell().onTap, isNotNull);

      await tester.tap(find.text('Clear all'));
      await settle(tester);
      expect(find.widgetWithText(TextField, 'standup'), findsNothing);
      expect(visibleTitles(tester), _newestFirst);
    });

    testWidgets('tag filter lists the tag library', (tester) async {
      await open(tester);
      await tester.tap(find.text('Filter'));
      await settle(tester);

      for (final label in ['meeting', 'quarterly', 'interview', 'hiring', 'ideas', 'standup']) {
        expect(find.text(label), findsWidgets);
      }
      await tester.tap(find.widgetWithText(InkWell, 'interview'));
      await settle(tester);
      expect(visibleTitles(tester), ['Interview: Anna K.']);
    });

    testWidgets('speaker filter lists the speaker library and narrows the list', (tester) async {
      await open(tester);
      await tester.tap(find.text('Filter'));
      await settle(tester);

      for (final name in ['Alexei Sorokin', 'Maria Chen', 'James Wright']) {
        expect(find.text(name), findsWidgets);
      }
      await tester.tap(find.widgetWithText(InkWell, 'James Wright'));
      await settle(tester);

      expect(visibleTitles(tester), ['Daily Standup 19 Jun', 'Product Ideas — Voice Memo', 'Interview: Anna K.', 'Quarterly Review Meeting']);
      expect(find.text('Filter · 1'), findsOneWidget);
    });

    testWidgets('typing in the speaker search narrows the checklist, not the page list', (tester) async {
      await open(tester);
      await tester.tap(find.text('Filter'));
      await settle(tester);

      await tester.enterText(find.byKey(const Key('speaker-filter-search')), 'mar');
      await settle(tester);

      expect(find.text('Maria Chen'), findsOneWidget);
      expect(find.text('Alexei Sorokin'), findsNothing);
      expect(find.text('James Wright'), findsNothing);
      expect(visibleTitles(tester), _newestFirst); // the search box only narrows the checklist

      await tester.enterText(find.byKey(const Key('speaker-filter-search')), 'zzz');
      await settle(tester);
      expect(find.text('No match'), findsOneWidget);
    });

    testWidgets('sort changes the order and the button label', (tester) async {
      await open(tester);
      await tester.tap(find.text('Sort'));
      await settle(tester);
      await tester.tap(find.text('Longest'));
      await settle(tester);

      expect(visibleTitles(tester).first, 'Economics Lecture — Part 2');
      expect(find.text('Longest'), findsNWidgets(2)); // option + button
    });

    testWidgets('the sort menu offers upload and recording dates, then length and name', (tester) async {
      await open(tester);
      await tester.tap(find.text('Sort'));
      await settle(tester);

      final labels = ['Newest uploaded', 'Oldest uploaded', 'Newest recorded', 'Oldest recorded', 'Longest', 'Name A→Z'];
      for (final label in labels) {
        expect(find.text(label), findsOneWidget, reason: label);
      }
      final tops = [for (final label in labels) tester.getTopLeft(find.text(label)).dy];
      expect(tops, [...tops]..sort(), reason: 'in this order, one under the other');
    });

    testWidgets('sorting by the recording date reorders the list and names the button', (tester) async {
      await open(tester);
      await tester.tap(find.text('Sort'));
      await settle(tester);
      await tester.tap(find.text('Newest recorded'));
      await settle(tester);

      expect(visibleTitles(tester).last, 'Interview: Anna K.'); // recorded the year before
      expect(visibleTitles(tester).indexOf('Quarterly Review Meeting'), 4); // recorded 20 Jun, uploaded 22 Jun
      expect(find.text('Newest recorded'), findsNWidgets(2)); // option + button
    });

    testWidgets('a row shows both dates, and the words do not change with the sort', (tester) async {
      await open(tester);
      Finder inRow(String title, Finder what) =>
          find.descendant(of: find.widgetWithText(TranscriptionRow, title), matching: what);

      // Quarterly Review: uploaded 22 Jun, recorded 20 Jun.
      expect(inRow('Quarterly Review Meeting', find.text('22 Jun')), findsOneWidget);
      expect(inRow('Quarterly Review Meeting', find.text('20 Jun')), findsOneWidget);
      expect(inRow('Quarterly Review Meeting', find.byIcon(Icons.cloud_upload_outlined)), findsOneWidget);
      expect(inRow('Quarterly Review Meeting', find.byIcon(Icons.mic_none_rounded)), findsOneWidget);

      await tester.tap(find.text('Sort'));
      await settle(tester);
      await tester.tap(find.text('Oldest recorded'));
      await settle(tester);

      expect(inRow('Quarterly Review Meeting', find.text('22 Jun')), findsOneWidget);
      expect(inRow('Quarterly Review Meeting', find.text('20 Jun')), findsOneWidget);
    });

    testWidgets('a row without a recording date has the upload date only; an older recording shows its year', (tester) async {
      await open(tester);
      Finder inRow(String title, Finder what) =>
          find.descendant(of: find.widgetWithText(TranscriptionRow, title), matching: what);

      expect(inRow('Product Ideas — Voice Memo', find.byIcon(Icons.cloud_upload_outlined)), findsOneWidget);
      expect(inRow('Product Ideas — Voice Memo', find.byIcon(Icons.mic_none_rounded)), findsNothing);

      final year = DateTime.now().year - 1;
      expect(inRow('Interview: Anna K.', find.text('21 Jun')), findsOneWidget); // uploaded this year: no year
      expect(inRow('Interview: Anna K.', find.text('4 Mar $year')), findsOneWidget);
    });

    testWidgets('hovering a date tells the full time and which date it is', (tester) async {
      await open(tester);

      expect(find.byTooltip('Uploaded 22 Jun · 14:41'), findsOneWidget);
      expect(find.byTooltip('Recorded 20 Jun · 09:12'), findsOneWidget);
    });

    testWidgets('sort panel stays inside the window', (tester) async {
      await open(tester);
      await tester.tap(find.text('Sort'));
      await settle(tester);

      expect(tester.getRect(find.text('Newest uploaded')).right, lessThanOrEqualTo(1280));
      expect(tester.getRect(find.text('Name A→Z')).left, greaterThan(0));
    });

    testWidgets('a finished row opens its detail, others do nothing', (tester) async {
      await open(tester);

      await tester.tap(find.text('Podcast Episode #47'));
      await settle(tester);
      // Still on the list.
      expect(find.byType(TranscriptionRow), findsWidgets);

      await tester.tap(find.text('Quarterly Review Meeting'));
      await settle(tester);
      expect(find.byType(TranscriptionRow), findsNothing);
      expect(find.text('Overview'), findsOneWidget); // the detail page's tab
    });

    testWidgets('only a finished row looks clickable: the others have no hand cursor and no hover highlight', (tester) async {
      await open(tester);

      MouseCursor cursorOf(String title) {
        final row = find.ancestor(of: find.text(title), matching: find.byType(TranscriptionRow));
        return tester.widget<MouseRegion>(find.descendant(of: row, matching: find.byType(MouseRegion)).first).cursor;
      }

      expect(cursorOf('Quarterly Review Meeting'), SystemMouseCursors.click);
      expect(cursorOf('Podcast Episode #47'), SystemMouseCursors.basic); // processing

      // Hovering a row that is not ready does not light it up either.
      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await mouse.addPointer(location: Offset.zero);
      addTearDown(mouse.removePointer);
      await mouse.moveTo(tester.getCenter(find.text('Podcast Episode #47')));
      await tester.pump(const Duration(milliseconds: 200));
      final row = find.ancestor(of: find.text('Podcast Episode #47'), matching: find.byType(TranscriptionRow));
      final box = tester.widget<AnimatedContainer>(find.descendant(of: row, matching: find.byType(AnimatedContainer)).first).decoration as BoxDecoration;
      expect(box.boxShadow ?? const <BoxShadow>[], isEmpty);

      await mouse.moveTo(tester.getCenter(find.text('Quarterly Review Meeting')));
      await tester.pump(const Duration(milliseconds: 200));
      final done = find.ancestor(of: find.text('Quarterly Review Meeting'), matching: find.byType(TranscriptionRow));
      final doneBox = tester.widget<AnimatedContainer>(find.descendant(of: done, matching: find.byType(AnimatedContainer)).first).decoration as BoxDecoration;
      expect(doneBox.boxShadow, isNotEmpty); // a finished row does
    });

    testWidgets('filters survive leaving and returning to the page', (tester) async {
      await open(tester);
      await tester.enterText(find.byType(TextField), 'interview');
      await settle(tester);

      await tester.tap(find.text('Tags'));
      await settle(tester);
      await tester.tap(find.text('Transcriptions').first);
      await settle(tester);

      expect(visibleTitles(tester), ['Interview: Anna K.']);
      expect(find.widgetWithText(TextField, 'interview'), findsOneWidget);
    });
  });
}
