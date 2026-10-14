import 'package:flutter/foundation.dart';

import '../services/audio_backend.dart';

typedef AudioBackendFactory = AudioBackend Function({
  required void Function(Duration position) onPosition,
  required void Function(Duration duration) onDuration,
  required void Function() onEnded,
  required void Function(String message) onError,
});

/// Drives the floating audio player bar on the Transcript tab: one instance per open transcription, created
/// and disposed by the detail page (playback has no reason to continue once you leave it). [createBackend]
/// defaults to the real `<audio>` element; tests give their own to play "audio" without a browser.
class AudioPlayerController extends ChangeNotifier {
  AudioPlayerController({AudioBackendFactory? createBackend}) : _createBackend = createBackend ?? createAudioBackend;

  final AudioBackendFactory _createBackend;
  AudioBackend? _backend;

  bool _playing = false;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  String? _error;
  bool _unavailable = false;

  /// Null until [load] gives it a source — the bar has nothing to show yet.
  bool get hasSource => _backend != null;
  bool get playing => _playing;
  Duration get position => _position;
  Duration get duration => _duration;
  String? get error => _error;

  /// The recording has audio on record but the file is gone for good: the bar says so instead of offering to play.
  bool get unavailable => _unavailable;

  void markUnavailable() {
    _unavailable = true;
    notifyListeners();
  }

  /// Points the player at [url] (from `TranscriptionRepository.audioUrl`) and resets position, duration and
  /// any earlier error. Does not start playback on its own.
  void load(String url) {
    _backend?.dispose();
    _position = Duration.zero;
    _duration = Duration.zero;
    _error = null;
    _unavailable = false;
    _playing = false;
    _backend = _createBackend(onPosition: _onPosition, onDuration: _onDuration, onEnded: _onEnded, onError: _onError)
      ..setSource(url);
    notifyListeners();
  }

  Future<void> togglePlay() async {
    final backend = _backend;
    if (backend == null) return;
    if (_playing) {
      backend.pause();
      _playing = false;
      notifyListeners();
    } else {
      _error = null;
      await backend.play();
      _playing = true;
      notifyListeners();
    }
  }

  /// Also updates [position] right away, so the bar's scrubber and time don't wait for the element's own
  /// next `timeupdate` to catch up.
  void seek(Duration position) {
    _backend?.seek(position);
    _position = position;
    notifyListeners();
  }

  void _onPosition(Duration position) {
    _position = position;
    notifyListeners();
  }

  void _onDuration(Duration duration) {
    _duration = duration;
    notifyListeners();
  }

  void _onEnded() {
    _playing = false;
    _position = _duration;
    notifyListeners();
  }

  void _onError(String message) {
    _error = message;
    _playing = false;
    notifyListeners();
  }

  @override
  void dispose() {
    _backend?.dispose();
    super.dispose();
  }
}
