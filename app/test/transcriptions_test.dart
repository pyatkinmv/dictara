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

    testWidgets('sort panel stays inside the window', (tester) async {
      await open(tester);
      await tester.tap(find.text('Sort'));
      await settle(tester);

      expect(tester.getRect(find.text('Newest first')).right, lessThanOrEqualTo(1280));
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
