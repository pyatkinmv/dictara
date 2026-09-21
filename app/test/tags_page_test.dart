import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'support/fake_transcription_repository.dart';
import 'package:dictara_web/models/tag.dart';
import 'package:dictara_web/widgets/transcription_row.dart';

import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('FakeTranscriptionRepository tag library', () {
    test('usage is derived from the transcriptions that carry each tag', () async {
      final repo = FakeTranscriptionRepository();
      final entries = {for (final e in (await repo.tagLibrary()).entries) e.tag.label: e};

      expect(entries.keys, ['meeting', 'quarterly', 'interview', 'hiring', 'ideas', 'standup']);
      expect(entries['meeting']!.transcriptions, ['Quarterly Review Meeting', 'Daily Standup 19 Jun']);
      expect(entries['meeting']!.count, 2);
      expect(entries['quarterly']!.transcriptions, ['Quarterly Review Meeting']);
      expect(entries['meeting']!.description, startsWith('Regular team meetings'));
    });

    test('creating a tag lowercases it, stores the description and takes the next color', () async {
      final repo = FakeTranscriptionRepository();
      final tag = await repo.createTag('  Budget ', '  Money talks ');

      expect(tag.label, 'budget');
      expect(tag.color, libraryPalette[6 % libraryPalette.length]);
      final entry = (await repo.tagLibrary()).entries.last;
      expect(entry.tag.label, 'budget');
      expect(entry.description, 'Money talks');
      expect(entry.transcriptions, isEmpty);
    });

    test('updating renames the tag everywhere and keeps its color', () async {
      final repo = FakeTranscriptionRepository();
      await repo.updateTag('meeting', newLabel: 'Sync', description: 'New text');

      final library = (await repo.tagLibrary()).entries;
      final sync = library.firstWhere((e) => e.tag.label == 'sync');
      expect(sync.tag.color, const Color(0xFF2563EB));
      expect(sync.description, 'New text');
      expect(sync.transcriptions, ['Quarterly Review Meeting', 'Daily Standup 19 Jun']);
      expect(library.any((e) => e.tag.label == 'meeting'), isFalse);
      expect((await repo.get('0'))!.item.tags.map((t) => t.label), ['sync', 'quarterly']);
      expect((await repo.tags()).map((t) => t.label), contains('sync'));
    });

    test('deleting removes the tag from the library and from every transcription', () async {
      final repo = FakeTranscriptionRepository();
      final affected = await repo.deleteTag('meeting');

      expect(affected, 2);
      expect((await repo.tags()).map((t) => t.label), isNot(contains('meeting')));
      expect((await repo.get('0'))!.item.tags.map((t) => t.label), ['quarterly']);
      expect((await repo.get('5'))!.item.tags.map((t) => t.label), ['standup']);
      expect(await repo.deleteTag('meeting'), 0); // already gone
    });
  });

  group('Tags page', () {
    Future<void> openTags(WidgetTester tester, {FakeTranscriptionRepository? repo, Size? size}) async {
      if (size == null) {
        useDesktopSize(tester);
      } else {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
      }
      await tester.pumpWidget(DictaraApp(initialLocation: '/tags', repository: repo ?? FakeTranscriptionRepository()));
      await settle(tester);
    }

    Finder dialogField(int index) => find.byType(TextField).at(index);

    testWidgets('shows the header, the hint and a card per tag', (tester) async {
      await openTags(tester);

      expect(find.text('6 tags · used in 4 files'), findsOneWidget);
      expect(find.text('Add tag'), findsNWidgets(2)); // header button + add tile
      expect(
        find.text('Tag descriptions help Dictara automatically suggest and apply tags to new transcriptions.'),
        findsOneWidget,
      );
      for (final label in ['meeting', 'quarterly', 'interview', 'hiring', 'ideas', 'standup']) {
        expect(find.text(label), findsOneWidget, reason: label);
      }
      expect(find.text('Regular team meetings, standups, and group discussions.'), findsOneWidget);
    });

    testWidgets('a card lists its usage: count and the first two transcriptions', (tester) async {
      await openTags(tester);

      expect(find.text('2 files'), findsOneWidget); // meeting
      expect(find.text('1 file'), findsNWidgets(5));
      expect(find.text('Daily Standup 19 Jun'), findsWidgets);
      expect(find.text('Interview: Anna K.'), findsNWidgets(2)); // interview and hiring
    });

    testWidgets('more than two transcriptions collapse into "+N more"', (tester) async {
      final repo = FakeTranscriptionRepository();
      await repo.addTag('4', 'meeting'); // a third one
      await openTags(tester, repo: repo);

      expect(find.text('3 files'), findsOneWidget);
      expect(find.text('+1 more'), findsOneWidget);
      // Oldest first: Quarterly, Product Ideas (now tagged too), Daily Standup.
      // The first two are chips of the meeting card; the third is folded away,
      // so "Daily Standup" only appears on the standup card.
      expect(find.text('Product Ideas — Voice Memo'), findsNWidgets(2)); // meeting + ideas cards
      expect(find.text('Daily Standup 19 Jun'), findsOneWidget); // standup card only
    });

    testWidgets('a tag without a description or usage says so', (tester) async {
      final repo = FakeTranscriptionRepository();
      await repo.createTag('empty', '');
      await openTags(tester, repo: repo);

      expect(find.text('No description yet'), findsOneWidget);
      expect(find.text('Not used yet'), findsOneWidget);
      expect(find.text('0 files'), findsOneWidget);
    });

    testWidgets('the grid fits as many columns as the window allows', (tester) async {
      await openTags(tester); // 1280 wide: 4 columns
      final tops = [for (final l in ['meeting', 'quarterly', 'interview', 'hiring', 'ideas']) tester.getTopLeft(find.text(l)).dy];
      expect(tops[0], tops[1]);
      expect(tops[0], tops[2]);
      expect(tops[0], tops[3]);
      expect(tops[4], greaterThan(tops[0])); // the fifth wraps to a second row
    });

    testWidgets('a narrow window gets fewer columns', (tester) async {
      await openTags(tester, size: const Size(800, 900)); // content ~ 524 px: 2 columns
      final meeting = tester.getTopLeft(find.text('meeting'));
      final quarterly = tester.getTopLeft(find.text('quarterly'));
      final interview = tester.getTopLeft(find.text('interview'));
      expect(quarterly.dy, meeting.dy);
      expect(interview.dy, greaterThan(meeting.dy));
    });

    testWidgets('cards of one row are equally tall', (tester) async {
      final repo = FakeTranscriptionRepository();
      await repo.updateTag('meeting', newLabel: 'meeting', description: 'A long description ' * 12); // 3 lines
      await openTags(tester, repo: repo);

      Rect card(String label) => tester.getRect(
            find.ancestor(of: find.text(label), matching: find.byType(AnimatedContainer)).first,
          );
      expect(card('meeting').height, card('quarterly').height);
      expect(card('meeting').height, card('hiring').height);
    });

    group('create', () {
      testWidgets('the header button opens an empty dialog', (tester) async {
        await openTags(tester);

        await tester.tap(find.text('Add tag').first);
        await settle(tester);

        expect(find.text('New tag'), findsOneWidget);
        expect(find.text('TAG NAME'), findsOneWidget);
        expect(find.text('DESCRIPTION'), findsOneWidget);
        expect(find.text('Descriptions help Dictara auto-tag future transcriptions.'), findsOneWidget);
        expect(find.text('e.g. meeting'), findsOneWidget); // placeholder
        expect(tester.widget<TextField>(dialogField(0)).controller!.text, isEmpty);
      });

      testWidgets('the add tile opens the same dialog', (tester) async {
        await openTags(tester);
        await tester.tap(find.text('Add tag').last);
        await settle(tester);
        expect(find.text('New tag'), findsOneWidget);
      });

      testWidgets('saving adds a card with the name and description', (tester) async {
        await openTags(tester);
        await tester.tap(find.text('Add tag').first);
        await settle(tester);

        await tester.enterText(dialogField(0), 'Budget');
        await tester.enterText(dialogField(1), 'Money talks');
        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('New tag'), findsNothing);
        expect(find.text('budget'), findsOneWidget); // lowercased, like the library elsewhere
        expect(find.text('Money talks'), findsOneWidget);
        expect(find.text('7 tags · used in 4 files'), findsOneWidget);
      });

      testWidgets('Enter in the name field saves', (tester) async {
        await openTags(tester);
        await tester.tap(find.text('Add tag').first);
        await settle(tester);

        await tester.enterText(dialogField(0), 'quick');
        await tester.testTextInput.receiveAction(TextInputAction.done);
        await settle(tester);

        expect(find.text('New tag'), findsNothing);
        expect(find.text('quick'), findsOneWidget);
      });

      testWidgets('an empty name does nothing', (tester) async {
        await openTags(tester);
        await tester.tap(find.text('Add tag').first);
        await settle(tester);

        await tester.enterText(dialogField(0), '   ');
        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('New tag'), findsOneWidget); // still open
        expect(find.text('6 tags · used in 4 files'), findsOneWidget);
      });

      testWidgets('a name that exists is rejected until it is changed', (tester) async {
        await openTags(tester);
        await tester.tap(find.text('Add tag').first);
        await settle(tester);

        await tester.enterText(dialogField(0), 'MEETING');
        await tester.tap(find.text('Save'));
        await settle(tester);
        expect(find.text('A tag with this name already exists.'), findsOneWidget);
        expect(find.text('New tag'), findsOneWidget);

        await tester.enterText(dialogField(0), 'meetings');
        await settle(tester);
        expect(find.text('A tag with this name already exists.'), findsNothing);
      });

      testWidgets('Cancel, the close button, Escape and the backdrop discard it', (tester) async {
        await openTags(tester);

        Future<void> open() async {
          await tester.tap(find.text('Add tag').first);
          await settle(tester);
          await tester.enterText(dialogField(0), 'x');
          expect(find.text('New tag'), findsOneWidget);
        }

        await open();
        await tester.tap(find.text('Cancel'));
        await settle(tester);
        expect(find.text('New tag'), findsNothing);

        await open();
        await tester.tap(find.byIcon(Icons.close_rounded).last);
        await settle(tester);
        expect(find.text('New tag'), findsNothing);

        await open();
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await settle(tester);
        expect(find.text('New tag'), findsNothing);

        await open();
        await tester.tapAt(const Offset(20, 20));
        await settle(tester);
        expect(find.text('New tag'), findsNothing);

        expect(find.text('6 tags · used in 4 files'), findsOneWidget); // nothing was created
      });
    });

    group('edit', () {
      testWidgets('opens filled with the tag and saves changes to the card', (tester) async {
        await openTags(tester);

        await tester.tap(find.byTooltip('Edit quarterly'));
        await settle(tester);
        expect(find.text('Edit tag'), findsOneWidget);
        expect(tester.widget<TextField>(dialogField(0)).controller!.text, 'quarterly');
        expect(tester.widget<TextField>(dialogField(1)).controller!.text, startsWith('End-of-quarter'));

        await tester.enterText(dialogField(0), 'Quarter');
        await tester.enterText(dialogField(1), 'Quarter reviews');
        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('quarterly'), findsNothing);
        expect(find.text('quarter'), findsOneWidget);
        expect(find.text('Quarter reviews'), findsOneWidget);
        expect(find.text('6 tags · used in 4 files'), findsOneWidget); // usage is unchanged
      });

      testWidgets('keeping the tag\'s own name is not a duplicate', (tester) async {
        await openTags(tester);
        await tester.tap(find.byTooltip('Edit ideas'));
        await settle(tester);

        await tester.enterText(dialogField(1), 'Just new text');
        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('Edit tag'), findsNothing);
        expect(find.text('Just new text'), findsOneWidget);
      });

      testWidgets('renaming to another existing tag is rejected', (tester) async {
        await openTags(tester);
        await tester.tap(find.byTooltip('Edit ideas'));
        await settle(tester);

        await tester.enterText(dialogField(0), 'hiring');
        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('A tag with this name already exists.'), findsOneWidget);
      });

      testWidgets('the rename reaches the transcriptions', (tester) async {
        await openTags(tester);
        await tester.tap(find.byTooltip('Edit meeting'));
        await settle(tester);
        await tester.enterText(dialogField(0), 'sync');
        await tester.tap(find.text('Save'));
        await settle(tester);

        await tester.tap(find.text('Transcriptions').first);
        await settle(tester);
        final row = tester.widget<TranscriptionRow>(find.widgetWithText(TranscriptionRow, 'Quarterly Review Meeting'));
        expect(row.item.tags.map((t) => t.label), ['sync', 'quarterly']);
      });
    });

    group('delete', () {
      testWidgets('asks first, naming the tag and how many transcriptions lose it', (tester) async {
        await openTags(tester);

        await tester.tap(find.byTooltip('Delete meeting'));
        await settle(tester);

        expect(find.text('Delete "meeting"?'), findsOneWidget);
        expect(find.textContaining('This tag will be removed from 2 transcriptions'), findsOneWidget);
        expect(find.textContaining('This cannot be undone.'), findsOneWidget);
      });

      testWidgets('the wording follows the count', (tester) async {
        final repo = FakeTranscriptionRepository();
        await repo.createTag('empty', '');
        await openTags(tester, repo: repo);

        await tester.tap(find.byTooltip('Delete quarterly'));
        await settle(tester);
        expect(find.textContaining('removed from 1 transcription.'), findsOneWidget);
        await tester.tap(find.text('Cancel'));
        await settle(tester);

        await tester.tap(find.byTooltip('Delete empty'));
        await settle(tester);
        expect(find.textContaining('removed from 0 transcriptions.'), findsOneWidget);
      });

      testWidgets('Cancel keeps the tag', (tester) async {
        await openTags(tester);
        await tester.tap(find.byTooltip('Delete meeting'));
        await settle(tester);

        await tester.tap(find.text('Cancel'));
        await settle(tester);

        expect(find.text('Delete "meeting"?'), findsNothing);
        expect(find.text('6 tags · used in 4 files'), findsOneWidget);
      });

      testWidgets('Delete removes the card and the tag from the transcriptions', (tester) async {
        await openTags(tester);
        await tester.tap(find.byTooltip('Delete meeting'));
        await settle(tester);

        await tester.tap(find.text('Delete').last);
        await settle(tester);

        expect(find.text('Delete "meeting"?'), findsNothing);
        expect(find.text('meeting'), findsNothing);
        expect(find.text('5 tags · used in 4 files'), findsOneWidget);

        await tester.tap(find.text('Transcriptions').first);
        await settle(tester);
        final row = tester.widget<TranscriptionRow>(find.widgetWithText(TranscriptionRow, 'Daily Standup 19 Jun'));
        expect(row.item.tags.map((t) => t.label), ['standup']);
      });

      testWidgets('a deleted tag stops filtering the list', (tester) async {
        await openTags(tester);

        // Filter the list by "meeting" first.
        await tester.tap(find.text('Transcriptions').first);
        await settle(tester);
        await tester.tap(find.text('Filter'));
        await settle(tester);
        await tester.tap(find.widgetWithText(InkWell, 'meeting'));
        await settle(tester);
        expect(find.byType(TranscriptionRow), findsNWidgets(2));
        await tester.tapAt(const Offset(700, 500)); // close the dropdown

        await tester.tap(find.text('Tags').first);
        await settle(tester);
        await tester.tap(find.byTooltip('Delete meeting'));
        await settle(tester);
        await tester.tap(find.text('Delete').last);
        await settle(tester);

        await tester.tap(find.text('Transcriptions').first);
        await settle(tester);
        expect(find.byType(TranscriptionRow), findsNWidgets(7)); // not stuck on an empty filter
        expect(find.text('Filter'), findsOneWidget); // no "Filter · 1"
      });
    });

    testWidgets('tags created elsewhere show up when the page opens', (tester) async {
      final repo = FakeTranscriptionRepository();
      await openTags(tester, repo: repo);
      expect(find.text('6 tags · used in 4 files'), findsOneWidget);

      // Put a new tag on a transcription from its detail page, then come back.
      await tester.tap(find.text('Transcriptions').first);
      await settle(tester);
      await tester.tap(find.text('Interview: Anna K.'));
      await settle(tester);
      await tester.tap(find.text('+ Add tag'));
      await settle(tester);
      await tester.enterText(find.byType(TextField), 'fresh');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await settle(tester);

      await tester.tap(find.text('Tags').first);
      await settle(tester);
      expect(find.text('fresh'), findsOneWidget);
      expect(find.text('7 tags · used in 4 files'), findsOneWidget);
    });
  });
}
