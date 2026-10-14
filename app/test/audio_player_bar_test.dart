import 'package:dictara_web/pages/detail/audio_player_bar.dart';
import 'package:dictara_web/services/audio_backend.dart';
import 'package:dictara_web/state/audio_player_controller.dart';
import 'package:dictara_web/theme/app_palette.dart';
import 'package:dictara_web/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeAudioBackend implements AudioBackend {
  _FakeAudioBackend(this.onPosition, this.onDuration, this.onEnded, this.onError);

  final void Function(Duration) onPosition;
  final void Function(Duration) onDuration;
  final void Function() onEnded;
  final void Function(String) onError;

  bool playing = false;

  @override
  void setSource(String url) {}
  @override
  Future<void> play() async => playing = true;
  @override
  void pause() => playing = false;
  @override
  void seek(Duration position) {}
  @override
  void dispose() {}
}

void main() {
  late _FakeAudioBackend backend;

  AudioPlayerController makeController() => AudioPlayerController(
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
    expect(find.byType(Slider), findsOneWidget);

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
}
