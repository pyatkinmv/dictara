import 'package:dictara_web/services/audio_backend.dart';
import 'package:dictara_web/state/audio_player_controller.dart';
import 'package:flutter_test/flutter_test.dart';

/// A backend the test can drive directly — calling `onPosition`/`onDuration`/`onEnded`/`onError` simulates
/// what a real `<audio>` element would report.
class _FakeAudioBackend implements AudioBackend {
  _FakeAudioBackend(this.onPosition, this.onDuration, this.onEnded, this.onError);

  final void Function(Duration position) onPosition;
  final void Function(Duration duration) onDuration;
  final void Function() onEnded;
  final void Function(String message) onError;

  String? source;
  bool playing = false;
  bool disposed = false;
  Duration? lastSeek;

  @override
  void setSource(String url) => source = url;

  @override
  Future<void> play() async => playing = true;

  @override
  void pause() => playing = false;

  @override
  void seek(Duration position) => lastSeek = position;

  @override
  void dispose() => disposed = true;
}

void main() {
  late _FakeAudioBackend backend;

  AudioPlayerController makeController() => AudioPlayerController(
    createBackend: ({required onPosition, required onDuration, required onEnded, required onError}) {
      backend = _FakeAudioBackend(onPosition, onDuration, onEnded, onError);
      return backend;
    },
  );

  test('has no source until load is called', () {
    final c = makeController();
    expect(c.hasSource, isFalse);
  });

  test('load sets the source on the backend and resets position, duration and error', () {
    final c = makeController();
    c.load('https://example.com/a.mp3');

    expect(backend.source, 'https://example.com/a.mp3');
    expect(c.hasSource, isTrue);
    expect(c.position, Duration.zero);
    expect(c.duration, Duration.zero);
    expect(c.error, isNull);
  });

  test('togglePlay flips between play and pause, on the controller and the backend', () async {
    final c = makeController();
    c.load('u');

    await c.togglePlay();
    expect(c.playing, isTrue);
    expect(backend.playing, isTrue);

    await c.togglePlay();
    expect(c.playing, isFalse);
    expect(backend.playing, isFalse);
  });

  test('seek updates position immediately, without waiting for the backend to report it', () {
    final c = makeController();
    c.load('u');

    c.seek(const Duration(seconds: 30));

    expect(c.position, const Duration(seconds: 30));
    expect(backend.lastSeek, const Duration(seconds: 30));
  });

  test('position and duration reported by the backend show up on the controller', () {
    final c = makeController();
    c.load('u');

    backend.onDuration(const Duration(minutes: 2));
    backend.onPosition(const Duration(seconds: 10));

    expect(c.duration, const Duration(minutes: 2));
    expect(c.position, const Duration(seconds: 10));
  });

  test('reaching the end stops playback and leaves the scrubber at the end', () async {
    final c = makeController();
    c.load('u');
    backend.onDuration(const Duration(minutes: 1));
    await c.togglePlay();

    backend.onEnded();

    expect(c.playing, isFalse);
    expect(c.position, const Duration(minutes: 1));
  });

  test('a backend error surfaces on the controller and stops playback', () async {
    final c = makeController();
    c.load('u');
    await c.togglePlay();

    backend.onError('Could not play this audio.');

    expect(c.error, 'Could not play this audio.');
    expect(c.playing, isFalse);
  });

  test('loading a new url disposes the previous backend', () {
    final c = makeController();
    c.load('first');
    final firstBackend = backend;

    c.load('second');

    expect(firstBackend.disposed, isTrue);
    expect(backend.source, 'second');
  });

  test('disposing the controller disposes the backend', () {
    final c = makeController();
    c.load('u');

    c.dispose();

    expect(backend.disposed, isTrue);
  });
}
