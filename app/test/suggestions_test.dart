import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/fake_transcription_repository.dart';
import 'package:dictara_web/models/speaker.dart';
import 'package:dictara_web/models/suggestions.dart';
import 'package:dictara_web/models/tag.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/transcription_detail.dart';
import 'package:dictara_web/widgets/button_spinner.dart';

import 'test_utils.dart';

const _blue = Color(0xFF2563EB);
const _grey = Color(0xFFAAAAAA);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('Suggestions', () {
    test('is empty once nothing is pending', () {
      expect(const Suggestions().isEmpty, isTrue);
      expect(const Suggestions(title: 'x').isEmpty, isFalse);
      expect(const Suggestions(tags: [Tag('a', _blue)]).isEmpty, isFalse);
      expect(const Suggestions(speakers: {1: Speaker('A', _blue)}).isEmpty, isFalse);
    });

    test('copyWith can drop the title and replace the lists', () {
      const s = Suggestions(title: 't', tags: [Tag('a', _blue)], speakers: {1: Speaker('A', _blue)});
      final left = s.copyWith(clearTitle: true, tags: const []);
      expect(left.title, isNull);
      expect(left.tags, isEmpty);
      expect(left.speakers.keys, [1]);
    });
  });

  group('TranscriptionDetail.speakerFor with suggestions', () {
    TranscriptionDetail detail({Suggestions? suggestions, Map<int, Speaker> speakers = const {}}) {
      return TranscriptionDetail(
        item: Transcription(
          id: 1,
          title: 'T',
          duration: const Duration(minutes: 1),
          uploadedAt: DateTime(2025),
          status: TranscriptionStatus.done,
        ),
        segments: const [],
        speakers: speakers,
        suggestions: suggestions,
        originalFilename: 'a',
        fileSizeBytes: 1,
      );
    }

    test('an unassigned slot is grey', () {
      expect(detail().speakerFor(2), const Speaker('Speaker 2', _grey));
    });

    test('a pending suggestion tints the slot but keeps its name', () {
      final d = detail(suggestions: const Suggestions(speakers: {2: Speaker('Maria', _blue)}));
      expect(d.speakerFor(2), const Speaker('Speaker 2', _blue));
      expect(d.speakerFor(1), const Speaker('Speaker 1', _grey));
    });

    test('a confirmed mapping wins over a suggestion', () {
      final d = detail(
        speakers: const {2: Speaker('Bob', Color(0xFF222222))},
        suggestions: const Suggestions(speakers: {2: Speaker('Maria', _blue)}),
      );
      expect(d.speakerFor(2).name, 'Bob');
    });
  });

  group('FakeTranscriptionRepository suggestions', () {
    late FakeTranscriptionRepository repo;
    setUp(() => repo = FakeTranscriptionRepository());

    test('only the last item carries the design\'s suggestions', () async {
      final s = (await repo.get(6))!.suggestions!;
      expect(s.title, 'Product Sync — Q3 Roadmap');
      expect(s.tags.map((t) => t.label), ['meeting', 'product', 'roadmap']);
      expect(s.speakers.keys, [1, 2, 3]);
      expect((await repo.get(0))!.suggestions, isNull);
    });

    test('accepting a tag adds it to the transcription, and to the library when new', () async {
      await repo.acceptTagSuggestion(6, 'product');

      final d = (await repo.get(6))!;
      expect(d.item.tags.map((t) => t.label), ['product']);
      expect(d.item.tags.single.color, const Color(0xFF0891B2)); // the AI's color
      expect(d.suggestions!.tags.map((t) => t.label), ['meeting', 'roadmap']);
      expect((await repo.tags()).map((t) => t.label), contains('product'));
    });

    test('accepting a suggested tag that the library has uses the library tag', () async {
      await repo.acceptTagSuggestion(6, 'meeting');
      expect((await repo.tags()).where((t) => t.label == 'meeting').length, 1);
      expect((await repo.get(6))!.item.tags.map((t) => t.label), ['meeting']);
    });

    test('rejecting a tag drops it without touching the transcription or the library', () async {
      await repo.rejectTagSuggestion(6, 'roadmap');

      final d = (await repo.get(6))!;
      expect(d.item.tags, isEmpty);
      expect(d.suggestions!.tags.map((t) => t.label), ['meeting', 'product']);
      expect((await repo.tags()).map((t) => t.label), isNot(contains('roadmap')));
    });

    test('accepting a speaker confirms the slot', () async {
      await repo.acceptSpeakerSuggestion(6, 2);

      final d = (await repo.get(6))!;
      expect(d.speakers[2]!.name, 'Maria Chen');
      expect(d.speakerFor(1).name, 'Speaker 1'); // the others are still pending
      expect(d.suggestions!.speakers.keys, [1, 3]);
    });

    test('rejecting a speaker leaves the slot unassigned and untinted', () async {
      await repo.rejectSpeakerSuggestion(6, 1);

      final d = (await repo.get(6))!;
      expect(d.speakers.containsKey(1), isFalse);
      expect(d.speakerFor(1), const Speaker('Speaker 1', _grey));
      expect(d.suggestions!.speakers.keys, [2, 3]);
    });

    test('accept all renames, tags, maps the speakers and drops the record', () async {
      await repo.acceptAllSuggestions(6);

      final d = (await repo.get(6))!;
      expect(d.item.title, 'Product Sync — Q3 Roadmap');
      expect(d.item.tags.map((t) => t.label), ['meeting', 'product', 'roadmap']);
      expect(d.speakers.values.map((s) => s.name), ['Alexei Sorokin', 'Maria Chen', 'James Wright']);
      expect(d.suggestions, isNull);
    });

    test('dismiss all drops the record and changes nothing else', () async {
      await repo.dismissAllSuggestions(6);

      final d = (await repo.get(6))!;
      expect(d.suggestions, isNull);
      expect(d.item.title, 'product_sync_2025_06_23.mp4');
      expect(d.item.tags, isEmpty);
      expect(d.speakers, isEmpty);
    });

    test('the record disappears when the last item is resolved, and stays gone', () async {
      await repo.acceptAllSuggestions(6);
      await repo.acceptTagSuggestion(6, 'meeting'); // nothing left to accept: a no-op
      expect((await repo.get(6))!.suggestions, isNull);
    });

    test('the latency callback delays only the suggestion actions', () async {
      var calls = 0;
      final slow = FakeTranscriptionRepository(latency: () {
        calls++;
        return Duration.zero;
      });
      await slow.rename(0, 'x');
      expect(calls, 0);
      await slow.rejectTagSuggestion(6, 'meeting');
      expect(calls, 1);
    });
  });

  group('Suggestions on the detail page', () {
    Future<void> openDetail(WidgetTester tester, {String id = '6', FakeTranscriptionRepository? repo}) async {
      tester.view.physicalSize = const Size(1280, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(DictaraApp(initialLocation: '/transcriptions/$id', repository: repo));
      await settle(tester);
    }

    testWidgets('the banner summarizes what is pending', (tester) async {
      await openDetail(tester);

      expect(find.text('Rename to:'), findsOneWidget);
      expect(find.text('Product Sync — Q3 Roadmap'), findsOneWidget);
      expect(find.text('+ 3 tags · 3 speakers'), findsOneWidget);
      expect(find.text('Accept all'), findsOneWidget);
    });

    testWidgets('records without suggestions have no banner', (tester) async {
      await openDetail(tester, id: '0');
      expect(find.text('Accept all'), findsNothing);
      expect(find.text('Rename to:'), findsNothing);
    });

    testWidgets('pending tags and speakers are ghost items next to the real ones', (tester) async {
      await openDetail(tester);

      // Ghost tags: label plus accept/dismiss.
      for (final label in ['meeting', 'product', 'roadmap']) {
        expect(find.text(label), findsOneWidget, reason: label);
      }
      expect(find.byTooltip('AI suggestion'), findsNWidgets(6)); // 3 tags + 3 speakers
      expect(find.byTooltip('Accept'), findsNWidgets(6));
      expect(find.byTooltip('Dismiss'), findsNWidgets(6));
      // Ghost speaker chips: slot → suggested name.
      expect(find.text('Speaker 2'), findsOneWidget);
      expect(find.text('Maria Chen'), findsOneWidget);
    });

    testWidgets('accepting a tag turns its ghost into a real tag and updates the banner', (tester) async {
      await openDetail(tester);

      await tester.tap(find.byTooltip('Accept').at(1)); // the "product" ghost
      await settle(tester);

      expect(find.byKey(const ValueKey('remove-tag-product')), findsOneWidget);
      expect(find.text('+ 2 tags · 3 speakers'), findsOneWidget);
      expect(find.byTooltip('AI suggestion'), findsNWidgets(5));
    });

    testWidgets('rejecting a tag just removes its ghost', (tester) async {
      await openDetail(tester);

      await tester.tap(find.byTooltip('Dismiss').first); // "meeting"
      await settle(tester);

      expect(find.text('meeting'), findsNothing);
      expect(find.text('+ 2 tags · 3 speakers'), findsOneWidget);
    });

    testWidgets('the singular form is used for one tag or one speaker', (tester) async {
      await openDetail(tester);

      await tester.tap(find.byTooltip('Dismiss').at(0)); // tags: 3 → 2
      await settle(tester);
      await tester.tap(find.byTooltip('Dismiss').at(0)); // → 1
      await settle(tester);
      expect(find.text('+ 1 tag · 3 speakers'), findsOneWidget);
    });

    testWidgets('accepting a speaker names the slot and the transcript', (tester) async {
      await openDetail(tester);
      expect(find.text('SPEAKER 2'), findsWidgets);

      // The speaker ghosts come after the three tag ghosts.
      await tester.tap(find.byTooltip('Accept').at(4)); // slot 2
      await settle(tester);

      expect(find.text('Speaker 2'), findsNothing); // no ghost for slot 2 any more
      expect(find.text('MARIA CHEN'), findsWidgets); // the transcript block
      expect(find.text('+ 3 tags · 2 speakers'), findsOneWidget);
    });

    testWidgets('accept all applies everything and hides the banner', (tester) async {
      await openDetail(tester);

      await tester.tap(find.text('Accept all'));
      await settle(tester);

      expect(find.text('Accept all'), findsNothing);
      expect(find.byTooltip('AI suggestion'), findsNothing);
      // Renamed in the breadcrumb, the header and the topbar.
      expect(find.text('Product Sync — Q3 Roadmap'), findsNWidgets(3));
      for (final label in ['meeting', 'product', 'roadmap']) {
        expect(find.byKey(ValueKey('remove-tag-$label')), findsOneWidget);
      }
      expect(find.text('ALEXEI SOROKIN'), findsWidgets);
    });

    testWidgets('dismiss all hides the banner and changes nothing', (tester) async {
      await openDetail(tester);

      await tester.tap(find.byTooltip('Dismiss all'));
      await settle(tester);

      expect(find.text('Accept all'), findsNothing);
      expect(find.byTooltip('AI suggestion'), findsNothing);
      expect(find.text('product_sync_2025_06_23.mp4'), findsWidgets); // still the old title
      expect(find.byKey(const ValueKey('remove-tag-meeting')), findsNothing); // nothing was added
    });

    testWidgets('the banner drops the rename and the separator parts as they resolve', (tester) async {
      await openDetail(tester);

      // Resolve every tag and speaker one by one: only the rename is left.
      for (var i = 0; i < 6; i++) {
        await tester.tap(find.byTooltip('Dismiss').first);
        await settle(tester);
      }
      expect(find.text('Rename to:'), findsOneWidget);
      expect(find.text('·'), findsNothing);
      expect(find.textContaining(RegExp(r'^\+ \d')), findsNothing); // no "+ 2 tags · …" part
      expect(find.text('Accept all'), findsOneWidget);
    });

    testWidgets('a resolved suggestion stays resolved after leaving and reopening', (tester) async {
      await openDetail(tester);
      await tester.tap(find.text('Accept all'));
      await settle(tester);

      await tester.tap(find.text('Transcriptions').last);
      await settle(tester);
      // The renamed record is in the list now; open it again.
      await tester.tap(find.text('Product Sync — Q3 Roadmap'));
      await settle(tester);

      expect(find.text('Accept all'), findsNothing);
      expect(find.byTooltip('AI suggestion'), findsNothing);
    });

    testWidgets('the rename shows in the list', (tester) async {
      await openDetail(tester);
      await tester.tap(find.text('Accept all'));
      await settle(tester);

      await tester.tap(find.text('Transcriptions').last);
      await settle(tester);
      expect(find.text('Product Sync — Q3 Roadmap'), findsOneWidget);
      expect(find.text('product_sync_2025_06_23.mp4'), findsNothing);
    });

    testWidgets('while an action runs its button shows a spinner and ignores clicks', (tester) async {
      final repo = FakeTranscriptionRepository(latency: () => const Duration(milliseconds: 500));
      await openDetail(tester, repo: repo);

      await tester.tap(find.text('Accept all'));
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.byType(ButtonSpinner), findsOneWidget);
      expect(find.text('Accept all'), findsNothing);

      // Nothing has been applied yet.
      expect(find.text('Rename to:'), findsOneWidget);

      await tester.pump(const Duration(milliseconds: 700));
      await tester.pump();
      expect(find.byType(ButtonSpinner), findsNothing);
      expect(find.text('Accept all'), findsNothing); // done: the banner is gone
    });

    testWidgets('each button has its own spinner', (tester) async {
      final repo = FakeTranscriptionRepository(latency: () => const Duration(milliseconds: 500));
      await openDetail(tester, repo: repo);

      await tester.tap(find.byTooltip('Accept').first);
      await tester.pump(const Duration(milliseconds: 100));
      await tester.tap(find.byTooltip('Dismiss').last);
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.byType(ButtonSpinner), findsNWidgets(2));

      await tester.pump(const Duration(milliseconds: 700));
      await tester.pump();
      expect(find.byType(ButtonSpinner), findsNothing);
    });

    testWidgets('the transcript tints unassigned slots with the suggestion colors until they are resolved', (tester) async {
      await openDetail(tester);
      // Pending: "Speaker N" headers, colored by the suggestions (not grey).
      final header = tester.widget<Text>(find.text('SPEAKER 1').first);
      expect(header.style!.color, const Color(0xFF0D9488));

      await tester.tap(find.byTooltip('Dismiss').at(3)); // slot 1 ghost
      await settle(tester);
      final rejected = tester.widget<Text>(find.text('SPEAKER 1').first);
      expect(rejected.style!.color, _grey);
    });
  });
}
