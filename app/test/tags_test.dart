import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/fake_transcription_repository.dart';
import 'package:dictara_web/models/tag.dart';
import 'package:dictara_web/widgets/transcription_row.dart';

import 'test_utils.dart';

const _dropdown = ValueKey('tag-dropdown');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('FakeTranscriptionRepository tags', () {
    test('adding a library tag links it to the transcription', () async {
      final repo = FakeTranscriptionRepository();
      final tag = await repo.addTag('0', 'ideas');

      expect(tag.label, 'ideas');
      expect(tag.color, const Color(0xFF0891B2)); // the library's own color
      expect((await repo.get('0'))!.item.tags.map((t) => t.label), ['meeting', 'quarterly', 'ideas']);
      expect((await repo.tags()).length, 6);
    });

    test('a new label is created in the library, lowercase, with the next palette color', () async {
      final repo = FakeTranscriptionRepository();
      final tag = await repo.addTag('0', '  Budget ');

      expect(tag.label, 'budget');
      expect(tag.color, libraryPalette[6 % libraryPalette.length]); // library had 6 tags
      expect((await repo.tags()).map((t) => t.label), contains('budget'));
      expect((await repo.get('0'))!.item.tags.map((t) => t.label), contains('budget'));

      // The next one gets the next color.
      expect((await repo.addTag('0', 'second')).color, libraryPalette[7 % libraryPalette.length]);
    });

    test('adding the same tag twice does not duplicate it', () async {
      final repo = FakeTranscriptionRepository();
      await repo.addTag('0', 'meeting');
      expect((await repo.get('0'))!.item.tags.where((t) => t.label == 'meeting').length, 1);
    });

    test('removing takes the tag off one transcription but keeps it in the library', () async {
      final repo = FakeTranscriptionRepository();
      await repo.removeTag('0', 'meeting');

      expect((await repo.get('0'))!.item.tags.map((t) => t.label), ['quarterly']);
      expect((await repo.get('5'))!.item.tags.map((t) => t.label), contains('meeting'));
      expect((await repo.tags()).map((t) => t.label), contains('meeting'));
    });
  });

  group('Tags row', () {
    Future<void> openDetail(WidgetTester tester, [String id = '0']) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(initialLocation: '/transcriptions/$id'));
      await settle(tester);
    }

    Future<void> openDropdown(WidgetTester tester) async {
      await tester.tap(find.text('+ Add tag'));
      await settle(tester);
    }

    Finder inDropdown(Finder f) => find.descendant(of: find.byKey(_dropdown), matching: f);

    testWidgets('shows the transcription\'s tags and the add button', (tester) async {
      await openDetail(tester);
      expect(find.text('meeting'), findsOneWidget);
      expect(find.text('quarterly'), findsOneWidget);
      expect(find.text('+ Add tag'), findsOneWidget);
    });

    testWidgets('a transcription without tags has no tag pills, only the add button', (tester) async {
      await openDetail(tester, '6');
      // No confirmed tags (its AI suggestions are ghost pills without a remove button).
      expect(find.byKey(const ValueKey('remove-tag-meeting')), findsNothing);
      expect(find.text('+ Add tag'), findsOneWidget);
    });

    testWidgets('the remove button takes the tag off, and the list follows', (tester) async {
      await openDetail(tester);

      await tester.tap(find.byKey(const ValueKey('remove-tag-meeting')));
      await settle(tester);
      expect(find.text('meeting'), findsNothing);
      expect(find.text('quarterly'), findsOneWidget);

      // Back on the list the row lost the tag too, but the tag is still in the library.
      await tester.tap(find.text('Transcriptions').last);
      await settle(tester);
      final row = tester.widget<TranscriptionRow>(find.widgetWithText(TranscriptionRow, 'Quarterly Review Meeting'));
      expect(row.item.tags.map((t) => t.label), ['quarterly']);
      await tester.tap(find.text('Filter'));
      await settle(tester);
      expect(find.text('meeting'), findsWidgets); // still a filter option
    });

    testWidgets('the dropdown offers only tags not yet on the transcription', (tester) async {
      await openDetail(tester);
      await openDropdown(tester);

      for (final label in ['interview', 'hiring', 'ideas', 'standup']) {
        expect(inDropdown(find.text(label)), findsOneWidget, reason: label);
      }
      expect(inDropdown(find.text('meeting')), findsNothing);
      expect(inDropdown(find.text('quarterly')), findsNothing);
      expect(find.byType(TextField), findsOneWidget);
    });

    testWidgets('picking a library tag adds it and closes the dropdown', (tester) async {
      await openDetail(tester);
      await openDropdown(tester);

      await tester.tap(inDropdown(find.text('ideas')));
      await settle(tester);

      expect(find.byKey(_dropdown), findsNothing);
      expect(find.text('ideas'), findsOneWidget); // the new pill
    });

    testWidgets('typing filters the tags and offers to create a new one', (tester) async {
      await openDetail(tester);
      await openDropdown(tester);

      await tester.enterText(find.byType(TextField), 'stand');
      await settle(tester);
      expect(inDropdown(find.text('standup')), findsOneWidget);
      expect(inDropdown(find.text('ideas')), findsNothing);
      expect(find.text('Create "stand"'), findsOneWidget);

      // An exact library name is not offered for creation.
      await tester.enterText(find.byType(TextField), 'STANDUP');
      await settle(tester);
      expect(find.textContaining('Create'), findsNothing);
      expect(inDropdown(find.text('standup')), findsOneWidget);

      // Nor is a tag that is already on this transcription.
      await tester.enterText(find.byType(TextField), 'meeting');
      await settle(tester);
      expect(find.textContaining('Create'), findsNothing);
    });

    testWidgets('creating a tag adds it here and to the library', (tester) async {
      await openDetail(tester);
      await openDropdown(tester);

      await tester.enterText(find.byType(TextField), 'Budget');
      await settle(tester);
      expect(find.text('Create "budget"'), findsOneWidget);
      await tester.tap(find.text('Create "budget"'));
      await settle(tester);

      expect(find.byKey(_dropdown), findsNothing);
      expect(find.text('budget'), findsOneWidget);

      // The library grew: the list's filter offers it.
      await tester.tap(find.text('Transcriptions').last);
      await settle(tester);
      await tester.tap(find.text('Filter'));
      await settle(tester);
      expect(find.text('budget'), findsWidgets);
    });

    testWidgets('Enter adds an existing tag or creates a new one', (tester) async {
      await openDetail(tester);

      await openDropdown(tester);
      await tester.enterText(find.byType(TextField), 'hiring');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await settle(tester);
      expect(find.text('hiring'), findsOneWidget);
      expect(find.byKey(_dropdown), findsNothing);

      await openDropdown(tester);
      await tester.enterText(find.byType(TextField), 'roadmap');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await settle(tester);
      expect(find.text('roadmap'), findsOneWidget);
    });

    testWidgets('Enter on a tag that is already there does nothing', (tester) async {
      await openDetail(tester);
      await openDropdown(tester);

      await tester.enterText(find.byType(TextField), 'meeting');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await settle(tester);

      expect(find.byKey(_dropdown), findsOneWidget); // still open
      expect(find.byKey(const ValueKey('remove-tag-meeting')), findsOneWidget); // no duplicate pill
    });

    testWidgets('Escape and tapping outside close the dropdown', (tester) async {
      await openDetail(tester);

      await openDropdown(tester);
      expect(find.byKey(_dropdown), findsOneWidget);
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);
      expect(find.byKey(_dropdown), findsNothing);

      await openDropdown(tester);
      await tester.tapAt(const Offset(800, 600));
      await settle(tester);
      expect(find.byKey(_dropdown), findsNothing);
    });

    testWidgets('once every tag is on the transcription the dropdown says so', (tester) async {
      await openDetail(tester);
      for (final label in ['interview', 'hiring', 'ideas', 'standup']) {
        await openDropdown(tester);
        await tester.tap(inDropdown(find.text(label)));
        await settle(tester);
      }

      await openDropdown(tester);
      expect(find.text('All tags already added'), findsOneWidget);
    });

    testWidgets('opening the dropdown again starts with an empty search', (tester) async {
      await openDetail(tester);
      await openDropdown(tester);
      await tester.enterText(find.byType(TextField), 'xyz');
      await settle(tester);
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);

      await openDropdown(tester);
      expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, isEmpty);
      expect(inDropdown(find.text('ideas')), findsOneWidget);
    });
  });
}
