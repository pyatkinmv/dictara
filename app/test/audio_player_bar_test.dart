import 'package:dictara_web/pages/detail/audio_player_bar.dart';
import 'package:dictara_web/services/audio_backend.dart';
import 'package:dictara_web/state/audio_player_controller.dart';
import 'package:dictara_web/theme/app_palette.dart';
import 'package:dictara_web/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/in_memory_volume_store.dart';

class _FakeAudioBackend implements AudioBackend {
  _FakeAudioBackend(this.onPosition, this.onDuration, this.onEnded, this.onError);

  final void Function(Duration) onPosition;
  final void Function(Duration) onDuration;
  final void Function() onEnded;
  final void Function(String) onError;

  bool playing = false;
  double? lastVolume;

  @override
  void setSource(String url) {}
  @override
  Future<void> play() async => playing = true;
  @override
  void pause() => playing = false;
  @override
  void seek(Duration position) {}
  @override
  void setVolume(double volume) => lastVolume = volume;
  @override
  void dispose() {}
}

void main() {
  late _FakeAudioBackend backend;

  AudioPlayerController makeController() => AudioPlayerController(
    volumeStore: InMemoryVolumeStore(),
    createBackend: ({required onPosition, required onDuration, required onEnded, required onError}) {
      backend = _FakeAudioBackend(onPosition, onDuration, onEnded, onError);
      return backend;
    },
  );

  Future<void> pump(WidgetTester tester, AudioPlayerController controller) => tester.pumpWidget(
    MaterialApp(
      theme: buildAppTheme(AppPalette.teal),
      home: Scaffold(body: Center(child: AudioPlayerBar(controller: controller))),
    ),
  );

  testWidgets('renders nothing until the controller has a source', (tester) async {
    final c = makeController();
    await pump(tester, c);

    expect(find.byType(Slider), findsNothing);
    expect(find.byIcon(Icons.play_arrow_rounded), findsNothing);
  });

  testWidgets('shows play, times and the slider once loaded, and tapping toggles to pause', (tester) async {
    final c = makeController();
    c.load('u');
    await pump(tester, c);

    expect(find.byIcon(Icons.play_arrow_rounded), findsOneWidget);
    expect(find.text('0:00'), findsWidgets); // position and duration both start at 0:00
    expect(find.byKey(const Key('audio-seek')), findsOneWidget);

    await tester.tap(find.byIcon(Icons.play_arrow_rounded));
    await tester.pump();

    expect(backend.playing, isTrue);
    expect(find.byIcon(Icons.pause_rounded), findsOneWidget);
    expect(find.byIcon(Icons.play_arrow_rounded), findsNothing);
  });

  testWidgets('shows the position and duration reported by the backend', (tester) async {
    final c = makeController();
    c.load('u');
    await pump(tester, c);
    backend.onDuration(const Duration(minutes: 14, seconds: 22));
    backend.onPosition(const Duration(seconds: 12));
    await tester.pump();

    expect(find.text('0:12'), findsOneWidget);
    expect(find.text('14:22'), findsOneWidget);
  });

  testWidgets('a backend error shows a warning badge with the message, not just a silently reset button', (tester) async {
    final c = makeController();
    c.load('u');
    await pump(tester, c);
    expect(find.byIcon(Icons.error_outline_rounded), findsNothing);

    backend.onError('Could not load audio');
    await tester.pump();

    expect(find.byIcon(Icons.play_arrow_rounded), findsOneWidget); // reverted, but not silently
    expect(find.byIcon(Icons.error_outline_rounded), findsOneWidget);
    expect(find.byTooltip('Could not load audio'), findsOneWidget);
  });

  testWidgets('starting playback again clears the earlier error badge', (tester) async {
    final c = makeController();
    c.load('u');
    await pump(tester, c);
    backend.onError('Could not load audio');
    await tester.pump();
    expect(find.byIcon(Icons.error_outline_rounded), findsOneWidget);

    await tester.tap(find.byIcon(Icons.play_arrow_rounded));
    await tester.pump();

    expect(find.byIcon(Icons.error_outline_rounded), findsNothing);
  });

  testWidgets('a recording whose file is gone shows a notice instead of a player', (tester) async {
    final c = makeController();
    await pump(tester, c);
    expect(find.byKey(const Key('audio-unavailable')), findsNothing);

    c.markUnavailable();
    await tester.pump();

    expect(find.text('Audio is no longer available'), findsOneWidget);
    expect(find.byType(Slider), findsNothing);
    expect(find.byIcon(Icons.play_arrow_rounded), findsNothing);
  });

  testWidgets('the player takes the width it is given, up to 640, instead of a fixed narrow pill', (tester) async {
    final c = makeController();
    c.load('u');
    await tester.binding.setSurfaceSize(const Size(1000, 600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await pump(tester, c);

    final pill = tester.getSize(find.byKey(const Key('audio-seek')).first);
    expect(pill.width, greaterThan(300)); // the seek slider used to be a fixed 220

    await tester.binding.setSurfaceSize(const Size(500, 600));
    await tester.pump();
    expect(tester.takeException(), isNull); // and it still fits a narrow window
  });

  testWidgets('the speaker mutes and unmutes, the slider sets the level, and the backend follows', (tester) async {
    final c = makeController();
    c.load('u');
    await pump(tester, c);
    expect(backend.lastVolume, 1.0);

    await tester.tap(find.byKey(const Key('audio-mute')));
    await tester.pump();
    expect(find.byIcon(Icons.volume_off_rounded), findsOneWidget);
    expect(backend.lastVolume, 0.0);

    await tester.tap(find.byKey(const Key('audio-mute')));
    await tester.pump();
    expect(find.byIcon(Icons.volume_up_rounded), findsOneWidget);
    expect(backend.lastVolume, 1.0);

    final slider = tester.widget<Slider>(find.byKey(const Key('audio-volume')));
    slider.onChanged!(0.3);
    await tester.pump();
    expect(backend.lastVolume, 0.3);
    expect(find.byIcon(Icons.volume_down_rounded), findsOneWidget);
  });
}
