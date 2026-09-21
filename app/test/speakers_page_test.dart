import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'support/fake_transcription_repository.dart';
import 'package:dictara_web/models/tag.dart';

import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('FakeTranscriptionRepository speaker library', () {
    test(
      'usage is derived from the transcriptions each speaker is heard in',
      () async {
        final repo = FakeTranscriptionRepository();
        final entries = {
          for (final e in await repo.speakerLibrary()) e.speaker.name: e,
        };

        expect(entries.keys, ['Alexei Sorokin', 'Maria Chen', 'James Wright']);
        // Slots 1-3 in the three-speaker recordings, slots 1-2 in the interview.
        expect(entries['Alexei Sorokin']!.transcriptions, [
          'Quarterly Review Meeting',
          'Interview: Anna K.',
          'Daily Standup 19 Jun',
        ]);
        expect(entries['James Wright']!.transcriptions, [
          'Quarterly Review Meeting',
          'Daily Standup 19 Jun',
        ]);
        expect(
          entries['James Wright']!.totalDuration,
          const Duration(minutes: 22, seconds: 22),
        );
        expect(
          entries['Maria Chen']!.description,
          startsWith('Head of Engineering'),
        );
      },
    );

    test('a hand-assigned speaker counts for that transcription', () async {
      final repo = FakeTranscriptionRepository();
      await repo.assignSpeaker('4', {
        0,
      }, 'Maria Chen'); // the voice memo has no diarization

      final maria = (await repo.speakerLibrary()).firstWhere(
        (e) => e.speaker.name == 'Maria Chen',
      );
      expect(maria.transcriptions, contains('Product Ideas — Voice Memo'));
    });

    test(
      'creating keeps the case, stores the description and takes the next color',
      () async {
        final repo = FakeTranscriptionRepository();
        final speaker = await repo.createSpeaker('  Ann Lee ', '  Designer ');

        expect(speaker.name, 'Ann Lee');
        expect(speaker.color, libraryPalette[3 % libraryPalette.length]);
        final entry = (await repo.speakerLibrary()).last;
        expect(entry.speaker, speaker);
        expect(entry.description, 'Designer');
        expect(entry.transcriptions, isEmpty);
        expect(entry.totalDuration, Duration.zero);
      },
    );

    test(
      'updating renames the speaker in every transcription and keeps the color',
      () async {
        final repo = FakeTranscriptionRepository();
        await repo.updateSpeaker(
          'Maria Chen',
          newName: 'Masha Chen',
          description: 'New text',
        );

        final library = await repo.speakerLibrary();
        final masha = library.firstWhere((e) => e.speaker.name == 'Masha Chen');
        expect(masha.speaker.color, const Color(0xFF7C3AED));
        expect(masha.description, 'New text');
        expect(masha.count, 3);
        expect(library.any((e) => e.speaker.name == 'Maria Chen'), isFalse);
        expect((await repo.get('0'))!.speakers[2]!.name, 'Masha Chen');
        expect(
          (await repo.get('2'))!.speakers[2]!.name,
          'Masha Chen',
        ); // never opened before
        expect(
          (await repo.speakers()).map((s) => s.name),
          contains('Masha Chen'),
        );
      },
    );

    test(
      'renaming follows manual assignments and pending suggestions',
      () async {
        final repo = FakeTranscriptionRepository();
        await repo.assignSpeaker('0', {0}, 'Maria Chen');
        await repo.updateSpeaker(
          'Maria Chen',
          newName: 'Masha Chen',
          description: '',
        );

        expect((await repo.get('0'))!.segmentSpeakers[0]!.name, 'Masha Chen');
        expect(
          (await repo.get('6'))!.suggestions!.speakers[2]!.name,
          'Masha Chen',
        );
      },
    );

    test(
      'deleting removes the speaker from the library and from every transcription',
      () async {
        final repo = FakeTranscriptionRepository();
        await repo.assignSpeaker('0', {0}, 'James Wright');
        final affected = await repo.deleteSpeaker('James Wright');

        expect(affected, 2);
        expect((await repo.speakers()).map((s) => s.name), [
          'Alexei Sorokin',
          'Maria Chen',
        ]);
        final detail = (await repo.get('0'))!;
        expect(detail.speakers.containsKey(3), isFalse);
        expect(detail.segmentSpeakers, isEmpty);
        expect((await repo.get('5'))!.speakers.containsKey(3), isFalse);
        expect(await repo.deleteSpeaker('James Wright'), 0); // already gone
      },
    );
  });

  group('Speakers page', () {
    Future<void> openSpeakers(
      WidgetTester tester, {
      FakeTranscriptionRepository? repo,
      Size? size,
    }) async {
      if (size == null) {
        useDesktopSize(tester);
      } else {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
      }
      await tester.pumpWidget(
        DictaraApp(initialLocation: '/speakers', repository: repo ?? FakeTranscriptionRepository()),
      );
      await settle(tester);
    }

    Finder dialogField(int index) => find.byType(TextField).at(index);

    TestGesture? mouse;
    setUp(() => mouse = null);

    Future<void> hoverCard(WidgetTester tester, String name) async {
      if (mouse == null) {
        mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
        addTearDown(mouse!.removePointer);
        await mouse!.addPointer(location: Offset.zero);
      }
      await mouse!.moveTo(tester.getCenter(find.text(name)));
      await tester.pump(const Duration(milliseconds: 200));
    }

    testWidgets('shows the header, the hint and a card per speaker', (
      tester,
    ) async {
      await openSpeakers(tester);

      expect(find.text('3 speakers'), findsOneWidget);
      expect(
        find.text('Add speaker'),
        findsNWidgets(2),
      ); // header button + add tile
      expect(
        find.text(
          'Named speakers help identify voices in future transcriptions automatically.',
        ),
        findsOneWidget,
      );
      for (final name in ['Alexei Sorokin', 'Maria Chen', 'James Wright']) {
        expect(find.text(name), findsOneWidget, reason: name);
      }
      expect(find.textContaining('Product lead.'), findsOneWidget);
    });

    testWidgets(
      'a card has initials, file count, total time and its transcriptions',
      (tester) async {
        await openSpeakers(tester);

        expect(find.text('AS'), findsOneWidget);
        expect(find.text('MC'), findsOneWidget);
        expect(find.text('JW'), findsOneWidget);
        expect(find.text('2 files'), findsOneWidget); // James
        expect(find.text('22 min'), findsOneWidget);
        expect(find.text('1h 9min'), findsNWidgets(2)); // Alexei and Maria
        expect(find.text('3 transcriptions'), findsNWidgets(2));
        expect(find.text('2 transcriptions'), findsOneWidget);
        expect(find.text('+1 more'), findsNWidgets(2));
      },
    );

    testWidgets('a speaker without a description or usage says so', (
      tester,
    ) async {
      final repo = FakeTranscriptionRepository();
      await repo.createSpeaker('Ann Lee', '');
      await openSpeakers(tester, repo: repo);

      expect(find.text('No description yet'), findsOneWidget);
      expect(find.text('Not used yet'), findsOneWidget);
      expect(find.text('0 transcriptions'), findsOneWidget);
      expect(find.text('AL'), findsOneWidget);
    });

    testWidgets('the grid needs 280 px per column', (tester) async {
      final repo = FakeTranscriptionRepository();
      await repo.createSpeaker('Ann Lee', '');
      await repo.createSpeaker('Bob Roe', '');
      await openSpeakers(
        tester,
        repo: repo,
        size: const Size(1280, 900),
      ); // content 1024 px: 3 columns

      double top(String name) => tester.getTopLeft(find.text(name)).dy;
      expect(top('Alexei Sorokin'), top('Maria Chen'));
      expect(top('Alexei Sorokin'), top('James Wright'));
      expect(
        top('Ann Lee'),
        greaterThan(top('Alexei Sorokin')),
      ); // the fourth starts a second row
      expect(top('Ann Lee'), top('Bob Roe'));
    });

    group('create', () {
      testWidgets('the header button opens the speaker dialog', (tester) async {
        await openSpeakers(tester);

        await tester.tap(find.text('Add speaker').first);
        await settle(tester);

        expect(find.text('New speaker'), findsOneWidget);
        expect(find.text('FULL NAME'), findsOneWidget);
        expect(find.text('e.g. Alexei Sorokin'), findsOneWidget);
        expect(
          find.text(
            "Describe this speaker's role and topics. Used for auto-identification.",
          ),
          findsOneWidget,
        );
        expect(
          tester.widget<TextField>(dialogField(0)).controller!.text,
          isEmpty,
        );
      });

      testWidgets('saving adds a card that keeps the typed case', (
        tester,
      ) async {
        await openSpeakers(tester);
        await tester.tap(find.text('Add speaker').last); // the tile
        await settle(tester);

        await tester.enterText(dialogField(0), 'Ann Lee');
        await tester.enterText(dialogField(1), 'Designer');
        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('New speaker'), findsNothing);
        expect(find.text('Ann Lee'), findsOneWidget);
        expect(find.text('Designer'), findsOneWidget);
        expect(find.text('4 speakers'), findsOneWidget);
      });

      testWidgets('an empty name does nothing', (tester) async {
        await openSpeakers(tester);
        await tester.tap(find.text('Add speaker').first);
        await settle(tester);

        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('New speaker'), findsOneWidget);
        expect(find.text('3 speakers'), findsOneWidget);
      });

      testWidgets(
        'an existing name (any case) is refused and the dialog stays',
        (tester) async {
          await openSpeakers(tester);
          await tester.tap(find.text('Add speaker').first);
          await settle(tester);

          await tester.enterText(dialogField(0), 'maria chen');
          await tester.tap(find.text('Save'));
          await settle(tester);

          expect(
            find.text('A speaker with this name already exists.'),
            findsOneWidget,
          );
          expect(find.text('New speaker'), findsOneWidget);

          await tester.enterText(dialogField(0), 'maria chen 2');
          await settle(tester);
          expect(
            find.text('A speaker with this name already exists.'),
            findsNothing,
          );
        },
      );

      testWidgets('Cancel and Escape close the dialog without creating', (
        tester,
      ) async {
        await openSpeakers(tester);
        await tester.tap(find.text('Add speaker').first);
        await settle(tester);
        await tester.enterText(dialogField(0), 'Ann Lee');
        await tester.tap(find.text('Cancel'));
        await settle(tester);
        expect(find.text('New speaker'), findsNothing);

        await tester.tap(find.text('Add speaker').first);
        await settle(tester);
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await settle(tester);
        expect(find.text('New speaker'), findsNothing);
        expect(find.text('3 speakers'), findsOneWidget);
      });
    });

    group('edit', () {
      testWidgets('the pencil opens the dialog filled with the speaker', (
        tester,
      ) async {
        await openSpeakers(tester);
        await hoverCard(tester, 'Maria Chen');

        await tester.tap(find.byTooltip('Edit Maria Chen'));
        await settle(tester);

        expect(find.text('Edit speaker'), findsOneWidget);
        expect(
          tester.widget<TextField>(dialogField(0)).controller!.text,
          'Maria Chen',
        );
        expect(
          tester.widget<TextField>(dialogField(1)).controller!.text,
          startsWith('Head of Engineering'),
        );
      });

      testWidgets('saving renames the speaker and updates the description', (
        tester,
      ) async {
        await openSpeakers(tester);
        await hoverCard(tester, 'Maria Chen');
        await tester.tap(find.byTooltip('Edit Maria Chen'));
        await settle(tester);

        await tester.enterText(dialogField(0), 'Masha Chen');
        await tester.enterText(dialogField(1), 'CTO');
        await tester.tap(find.text('Save'));
        await settle(tester);

        expect(find.text('Maria Chen'), findsNothing);
        expect(find.text('Masha Chen'), findsOneWidget);
        expect(find.text('CTO'), findsOneWidget);
        expect(find.text('MC'), findsOneWidget);
      });

      testWidgets(
        "keeping the own name is fine, taking another speaker's is not",
        (tester) async {
          await openSpeakers(tester);
          await hoverCard(tester, 'Maria Chen');
          await tester.tap(find.byTooltip('Edit Maria Chen'));
          await settle(tester);

          await tester.tap(find.text('Save')); // unchanged
          await settle(tester);
          expect(find.text('Edit speaker'), findsNothing);

          await hoverCard(tester, 'Maria Chen');
          await tester.tap(find.byTooltip('Edit Maria Chen'));
          await settle(tester);
          await tester.enterText(dialogField(0), 'James Wright');
          await tester.tap(find.text('Save'));
          await settle(tester);
          expect(
            find.text('A speaker with this name already exists.'),
            findsOneWidget,
          );
        },
      );
    });

    group('delete', () {
      Future<void> askDelete(WidgetTester tester, String name) async {
        await hoverCard(tester, name);
        await tester.tap(find.byTooltip('Delete $name'));
        await settle(tester);
      }

      testWidgets(
        'asks first, naming the speaker and how many transcriptions',
        (tester) async {
          await openSpeakers(tester);
          await askDelete(tester, 'James Wright');

          expect(find.text('Delete "James Wright"?'), findsOneWidget);
          expect(
            find.textContaining('This speaker will be removed from '),
            findsOneWidget,
          );
          expect(find.textContaining('2 transcriptions'), findsWidgets);
          expect(find.text('Delete'), findsOneWidget);
        },
      );

      testWidgets('Cancel keeps the speaker', (tester) async {
        await openSpeakers(tester);
        await askDelete(tester, 'James Wright');

        await tester.tap(find.text('Cancel'));
        await settle(tester);

        expect(find.text('James Wright'), findsOneWidget);
        expect(find.text('3 speakers'), findsOneWidget);
      });

      testWidgets('Delete removes the card', (tester) async {
        await openSpeakers(tester);
        await askDelete(tester, 'James Wright');

        await tester.tap(find.text('Delete'));
        await settle(tester);

        expect(find.text('James Wright'), findsNothing);
        expect(find.text('2 speakers'), findsOneWidget);
      });
    });
  });
}
