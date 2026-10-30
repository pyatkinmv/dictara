import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/pages/detail/phrase_text.dart';
import 'package:dictara_web/state/transcription_detail_controller.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';

import 'support/fake_transcription_repository.dart';
import 'test_utils.dart';

const _first = "Good morning everyone. Let's get started — we have a lot to cover today. "
    'The main focus is Q2 performance and planning the roadmap for the next quarter.';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  late FakeTranscriptionRepository repo;
  TestGesture? mouse;

  setUp(() => mouse = null);

  Future<void> openTranscript(WidgetTester tester, {Size? size}) async {
    if (size == null) {
      useDesktopSize(tester);
    } else {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
    }
    repo = FakeTranscriptionRepository();
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: repo, initialLocation: '/transcriptions/0'));
    await settle(tester);
    await tester.tap(find.text('Transcript'));
    await settle(tester);
  }

  /// Hovers over the first phrase (the pencil shows), clicks the pencil.
  Future<void> startEditing(WidgetTester tester, {int index = 0}) async {
    if (mouse == null) {
      mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await mouse!.addPointer(location: Offset.zero);
    }
    final gesture = mouse!;
    await gesture.moveTo(tester.getCenter(find.byWidgetPredicate((w) => w is PhraseText && w.index == index)));
    await tester.pump(const Duration(milliseconds: 200));
    await tester.tap(find.byKey(Key('edit-phrase-$index')));
    await settle(tester);
  }

  Finder field(int index) => find.byKey(Key('phrase-field-$index'));

  group('Editing the text of a phrase', () {
    testWidgets('the pencil opens a field with the phrase text, and tells the keys', (tester) async {
      await openTranscript(tester);

      await startEditing(tester);

      expect(field(0), findsOneWidget);
      expect(tester.widget<TextField>(field(0)).controller!.text, _first);
      expect(tester.widget<TextField>(field(0)).focusNode!.hasFocus, isTrue); // typing goes straight in
      expect(find.textContaining('Enter to save'), findsOneWidget);
      expect(find.byKey(const Key('save-phrase')), findsOneWidget);
      expect(find.byKey(const Key('cancel-phrase')), findsOneWidget);
    });

    testWidgets('Enter saves the new text, closes the field and leaves the other phrases alone', (tester) async {
      await openTranscript(tester);
      await startEditing(tester);

      await tester.enterText(field(0), '  Good evening everyone.  ');
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await settle(tester);

      expect(repo.editCalls.single.id, '0');
      expect(repo.editCalls.single.index, 0);
      expect(repo.editCalls.single.text, 'Good evening everyone.'); // trimmed
      expect(field(0), findsNothing);
      expect(find.textContaining('Good evening everyone.'), findsOneWidget);
      expect(find.textContaining('Good morning everyone'), findsNothing);
      expect(find.textContaining('Right. So Q2 was solid'), findsOneWidget); // the next phrase is as it was
    });

    testWidgets('the check button saves too', (tester) async {
      await openTranscript(tester);
      await startEditing(tester);

      await tester.enterText(field(0), 'Corrected.');
      await tester.tap(find.byKey(const Key('save-phrase')));
      await settle(tester);

      expect(repo.editCalls.single.text, 'Corrected.');
      expect(find.textContaining('Corrected.'), findsOneWidget);
    });

    testWidgets('Esc and the cross cancel: nothing is sent and the old text stays', (tester) async {
      await openTranscript(tester);
      await startEditing(tester);
      await tester.enterText(field(0), 'Never saved');

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);

      expect(repo.editCalls, isEmpty);
      expect(field(0), findsNothing);
      expect(find.textContaining('Good morning everyone'), findsOneWidget);

      await startEditing(tester);
      await tester.enterText(field(0), 'Never saved either');
      await tester.tap(find.byKey(const Key('cancel-phrase')));
      await settle(tester);

      expect(repo.editCalls, isEmpty);
      expect(find.textContaining('Never saved'), findsNothing);
    });

    testWidgets('an empty text is not saved: it says so and the field stays', (tester) async {
      await openTranscript(tester);
      await startEditing(tester);

      await tester.enterText(field(0), '   ');
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await settle(tester);

      expect(repo.editCalls, isEmpty);
      expect(find.text("A phrase can't be empty"), findsOneWidget);
      expect(field(0), findsOneWidget);
    });

    testWidgets('the same text closes the field without asking the gateway', (tester) async {
      await openTranscript(tester);
      await startEditing(tester);

      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await settle(tester);

      expect(repo.editCalls, isEmpty);
      expect(field(0), findsNothing);
    });

    testWidgets("a failure keeps the field and what was typed, and shows the gateway's words", (tester) async {
      await openTranscript(tester);
      await startEditing(tester);
      repo.editError = const GatewayException('The text has a character that cannot be saved', statusCode: 400);

      await tester.enterText(field(0), 'Typed text that must not be lost');
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await settle(tester);

      expect(find.text('The text has a character that cannot be saved'), findsOneWidget);
      expect(tester.widget<TextField>(field(0)).controller!.text, 'Typed text that must not be lost');

      // and it can be tried again
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await settle(tester);
      expect(field(0), findsNothing);
      expect(find.textContaining('Typed text that must not be lost'), findsOneWidget);
    });

    testWidgets('the corrected text is there when the transcription is opened again', (tester) async {
      await openTranscript(tester);
      await startEditing(tester);
      await tester.enterText(field(0), 'Saved for good.');
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await settle(tester);

      final controller = tester.element(find.byType(Scaffold).first).read<TranscriptionDetailController>();
      await controller.open('0');
      await settle(tester);

      expect(controller.current!.segments[0].text, 'Saved for good.');
      expect((await repo.get('0'))!.segments[0].text, 'Saved for good.');
    });

    testWidgets('the word count follows the change and the speaker and time of the phrase stay', (tester) async {
      await openTranscript(tester);
      final controller = tester.element(find.byType(Scaffold).first).read<TranscriptionDetailController>();
      final before = controller.current!;
      final words = before.wordCount!;
      final oldWords = before.segments[0].text.split(RegExp(r'\s+')).where((w) => w.isNotEmpty).length;

      await controller.editPhrase(0, 'Three words only');

      final after = controller.current!;
      expect(after.wordCount, words - oldWords + 3);
      expect(after.segments[0].start, before.segments[0].start);
      expect(after.segments[0].speakerSlot, before.segments[0].speakerSlot);
      expect(after.segments[1].text, before.segments[1].text);
    });

    testWidgets('on a phone the pencil is there without hovering, and the field fits', (tester) async {
      await openTranscript(tester, size: const Size(375, 812));

      expect(find.byKey(const Key('edit-phrase-0')), findsOneWidget);
      await tester.tap(find.byKey(const Key('edit-phrase-0')));
      await settle(tester);

      expect(field(0), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
