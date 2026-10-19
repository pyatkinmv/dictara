import 'package:dictara_web/models/speaker.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/transcription_detail.dart';
import 'package:dictara_web/pages/detail/transcript_view.dart';
import 'package:dictara_web/services/audio_backend.dart';
import 'package:dictara_web/state/audio_player_controller.dart';
import 'package:dictara_web/theme/app_palette.dart';
import 'package:dictara_web/theme/app_theme.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/in_memory_volume_store.dart';
import 'test_utils.dart';

class _Backend implements AudioBackend {
  _Backend(this.onPosition);

  final void Function(Duration) onPosition;
  bool playing = false;
  final seeks = <Duration>[];

  @override
  void setSource(String url) {}
  @override
  Future<void> play() async => playing = true;
  @override
  void pause() => playing = false;
  @override
  void seek(Duration position) => seeks.add(position);
  @override
  void setVolume(double volume) {}
  @override
  void dispose() {}
}

const _ann = Speaker('Ann', Color(0xFF111111));
const _bob = Speaker('Bob', Color(0xFF222222));

/// [count] phrases, 10 s apart, alternating between two speakers; the text of phrase i is "phrase i".
TranscriptionDetail _detail(int count) => TranscriptionDetail(
  item: Transcription(
    id: '1',
    title: 'Title',
    duration: Duration(seconds: count * 10),
    uploadedAt: DateTime(2026, 6, 22),
    status: TranscriptionStatus.done,
  ),
  segments: [for (var i = 0; i < count; i++) Segment(start: Duration(seconds: i * 10), speakerSlot: i.isEven ? 1 : 2, text: 'phrase $i')],
  speakers: const {1: _ann, 2: _bob},
  originalFilename: 'a.mp3',
  fileSizeBytes: 1024,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('TranscriptionDetail.phraseAt', () {
    final detail = _detail(3); // phrases start at 0:00, 0:10, 0:20

    test('is the last phrase that has started', () {
      expect(detail.phraseAt(Duration.zero), 0);
      expect(detail.phraseAt(const Duration(seconds: 9, milliseconds: 999)), 0);
      expect(detail.phraseAt(const Duration(seconds: 10)), 1);
      expect(detail.phraseAt(const Duration(seconds: 25)), 2);
    });

    test('stays on the last phrase after it', () {
      expect(detail.phraseAt(const Duration(hours: 1)), 2);
    });

    test('is null before the first phrase starts, and with no phrases at all', () {
      final late = TranscriptionDetail(
        item: detail.item,
        segments: const [Segment(start: Duration(seconds: 2), speakerSlot: 1, text: 'a')],
        speakers: const {},
        originalFilename: 'a.mp3',
        fileSizeBytes: 1,
      );
      expect(late.phraseAt(const Duration(seconds: 1)), isNull);
      expect(_detail(0).phraseAt(const Duration(seconds: 5)), isNull);
    });
  });

  group('AudioPlayerController.seekAndPlay', () {
    test('jumps to the place and starts playing from there', () async {
      late _Backend backend;
      final c = AudioPlayerController(
        volumeStore: InMemoryVolumeStore(),
        createBackend: ({required onPosition, required onDuration, required onEnded, required onError}) => backend = _Backend(onPosition),
      )..load('u');

      await c.seekAndPlay(const Duration(seconds: 30));

      expect(backend.seeks, [const Duration(seconds: 30)]);
      expect(c.position, const Duration(seconds: 30));
      expect(c.playing, isTrue);
      expect(backend.playing, isTrue);
    });

    test('while already playing it only jumps, without pausing', () async {
      late _Backend backend;
      final c = AudioPlayerController(
        volumeStore: InMemoryVolumeStore(),
        createBackend: ({required onPosition, required onDuration, required onEnded, required onError}) => backend = _Backend(onPosition),
      )..load('u');
      await c.togglePlay();

      await c.seekAndPlay(const Duration(seconds: 5));

      expect(c.playing, isTrue);
      expect(backend.playing, isTrue);
    });
  });

  group('Transcript while the audio plays', () {
    late _Backend backend;
    late AudioPlayerController player;

    Future<void> open(WidgetTester tester, {int count = 6, Size size = const Size(1280, 800), bool load = true}) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      player = AudioPlayerController(
        volumeStore: InMemoryVolumeStore(),
        createBackend: ({required onPosition, required onDuration, required onEnded, required onError}) => backend = _Backend(onPosition),
      );
      if (load) player.load('u');
      addTearDown(player.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: buildAppTheme(AppPalette.teal),
          home: Scaffold(
            body: TranscriptView(detail: _detail(count), library: const [], onAssign: (_, _, _) {}, player: player),
          ),
        ),
      );
      await tester.pump();
    }

    /// The audio element reports the position (as `timeupdate` does while playing).
    Future<void> at(WidgetTester tester, int seconds) async {
      backend.onPosition(Duration(seconds: seconds));
      await tester.pump(const Duration(milliseconds: 400));
    }

    TextStyle style(WidgetTester tester, String text) => tester.widget<Text>(find.text(text)).style!;

    Color accentOf(WidgetTester tester) => AppPalette.teal.accent;

    testWidgets('before it has played, nothing is marked and the text is as it always was', (tester) async {
      await open(tester);

      for (var i = 0; i < 6; i++) {
        expect(style(tester, 'phrase $i').fontWeight, FontWeight.w400, reason: 'phrase $i');
      }
      expect(style(tester, 'phrase 0').color, style(tester, 'phrase 5').color);
    });

    testWidgets('the phrase being spoken is marked: stronger text and a time in the accent color', (tester) async {
      await open(tester);
      await at(tester, 25); // phrase 2 started at 0:20

      expect(style(tester, 'phrase 2').fontWeight, FontWeight.w500);
      expect(style(tester, 'phrase 1').fontWeight, FontWeight.w400);
      expect(style(tester, 'phrase 3').fontWeight, FontWeight.w400);
      expect(tester.widget<Text>(find.text('0:20')).style!.color, accentOf(tester));
      expect(tester.widget<Text>(find.text('0:10')).style!.color, isNot(accentOf(tester)));
    });

    testWidgets('what was said is dark and what is ahead is faded', (tester) async {
      await open(tester);
      await at(tester, 25);

      final played = style(tester, 'phrase 0').color;
      final current = style(tester, 'phrase 2').color;
      final ahead = style(tester, 'phrase 4').color;
      expect(played, current);
      expect(ahead, isNot(played));
      expect(style(tester, 'phrase 3').color, ahead);
      expect(style(tester, 'phrase 1').color, played);
    });

    testWidgets('the mark moves on with the audio, and back when it is moved back', (tester) async {
      await open(tester);
      await at(tester, 5);
      expect(style(tester, 'phrase 0').fontWeight, FontWeight.w500);

      await at(tester, 31);
      expect(style(tester, 'phrase 0').fontWeight, FontWeight.w400);
      expect(style(tester, 'phrase 3').fontWeight, FontWeight.w500);

      await at(tester, 12);
      expect(style(tester, 'phrase 1').fontWeight, FontWeight.w500);
      expect(style(tester, 'phrase 3').fontWeight, FontWeight.w400);
    });

    testWidgets('in a pause between phrases the last one that began stays marked', (tester) async {
      await open(tester);
      await at(tester, 19); // 9 s into phrase 1, whatever it is that is quiet there

      expect(style(tester, 'phrase 1').fontWeight, FontWeight.w500);
    });

    testWidgets('only one phrase is marked at a time', (tester) async {
      await open(tester);
      await at(tester, 47);

      final marked = [for (var i = 0; i < 6; i++) if (style(tester, 'phrase $i').fontWeight == FontWeight.w500) i];
      expect(marked, [4]);
    });

    testWidgets('a play button shows when the pointer is over a row, and plays from that phrase', (tester) async {
      await open(tester);
      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      addTearDown(mouse.removePointer);
      await mouse.addPointer(location: Offset.zero);

      Finder button() => find.byTooltip('Play from here');
      double opacityOf(Finder f) => tester.widget<AnimatedOpacity>(find.ancestor(of: f, matching: find.byType(AnimatedOpacity)).first).opacity;

      await mouse.moveTo(tester.getCenter(find.text('phrase 3')));
      await tester.pump(const Duration(milliseconds: 300));
      expect(button(), findsWidgets);
      final row3 = find.ancestor(of: find.text('phrase 3'), matching: find.byType(MouseRegion)).first;
      final button3 = find.descendant(of: find.ancestor(of: row3, matching: find.byType(Container)).first, matching: button());
      expect(opacityOf(button3.first), 1);

      await tester.tap(button3.first);
      await tester.pump();

      expect(backend.seeks, [const Duration(seconds: 30)]); // phrase 3 starts at 0:30
      expect(backend.playing, isTrue);
    });

    testWidgets('without a pointer over it a row shows no play button, and without a source none works', (tester) async {
      await open(tester, load: false); // the audio is not there (yet)
      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      addTearDown(mouse.removePointer);
      await mouse.addPointer(location: Offset.zero);
      await mouse.moveTo(tester.getCenter(find.text('phrase 3')));
      await tester.pump(const Duration(milliseconds: 300));

      final opacities = tester.widgetList<AnimatedOpacity>(find.ancestor(of: find.byTooltip('Play from here'), matching: find.byType(AnimatedOpacity)));
      expect(opacities.every((o) => o.opacity == 0), isTrue);
    });

    testWidgets('on a phone there is no play button column', (tester) async {
      await open(tester, size: const Size(375, 800));

      expect(find.byTooltip('Play from here'), findsNothing);
    });

    testWidgets('the list follows the audio to a phrase that is out of sight', (tester) async {
      await open(tester, count: 200, size: const Size(1280, 500));
      expect(find.text('phrase 150'), findsNothing);

      await at(tester, 1505); // phrase 150
      await tester.pumpAndSettle();

      expect(find.text('phrase 150'), findsOneWidget);
      final rect = tester.getRect(find.text('phrase 150'));
      expect(rect.top, greaterThanOrEqualTo(0));
      expect(rect.bottom, lessThanOrEqualTo(500));
    });

    testWidgets('a phrase already well in view does not move the list', (tester) async {
      await open(tester, count: 200, size: const Size(1280, 800));
      await at(tester, 5); // phrase 0 at the top
      final before = tester.getTopLeft(find.text('phrase 1'));

      await at(tester, 15); // phrase 1, right below it

      expect(tester.getTopLeft(find.text('phrase 1')), before);
    });

    testWidgets('once the user scrolls by hand the list stops following, until play or a jump brings it back', (tester) async {
      await open(tester, count: 200, size: const Size(1280, 500));
      await player.togglePlay();
      await at(tester, 5);
      await tester.pumpAndSettle();
      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      addTearDown(mouse.removePointer);
      await mouse.addPointer(location: const Offset(400, 250));

      // The wheel, as a person would: scroll the list away from the playing phrase.
      tester.binding.handlePointerEvent(const PointerScrollEvent(position: Offset(400, 250), scrollDelta: Offset(0, 900)));
      await tester.pump(const Duration(milliseconds: 400));
      final scrolledTo = tester.getTopLeft(find.byType(Scrollable).first);
      expect(find.text('phrase 0'), findsNothing);

      // The audio goes on, a quarter of a second at a time as the element reports it, to phrase 1, out of sight now.
      for (var ms = 5250; ms <= 11000; ms += 250) {
        backend.onPosition(Duration(milliseconds: ms));
      }
      await tester.pumpAndSettle();
      expect(find.text('phrase 1'), findsNothing, reason: 'the list must stay where the user put it');
      expect(tester.getTopLeft(find.byType(Scrollable).first), scrolledTo);

      await at(tester, 600); // a jump in the audio: the list comes along again
      await tester.pumpAndSettle();
      expect(find.text('phrase 60'), findsOneWidget);
    });
  });
}
