import 'package:flutter/foundation.dart';

import '../services/audio_backend.dart';
import '../services/volume_store.dart';

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
  AudioPlayerController({AudioBackendFactory? createBackend, VolumeStore? volumeStore})
      : _createBackend = createBackend ?? createAudioBackend,
        _volumeStore = volumeStore ?? const BrowserVolumeStore() {
    _level = (_volumeStore.read() ?? 1.0).clamp(0.0, 1.0);
    _lastAudible = _level > 0 ? _level : 0.5;
  }

  final VolumeStore _volumeStore;

  final AudioBackendFactory _createBackend;
  AudioBackend? _backend;

  bool _playing = false;
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  String? _error;
  bool _unavailable = false;

  /// What is heard, 0..1 (0 is muted), and the audible level that unmuting goes back to.
  double _level = 1.0;
  double _lastAudible = 1.0;

  /// Null until [load] gives it a source — the bar has nothing to show yet.
  bool get hasSource => _backend != null;
  bool get playing => _playing;
  Duration get position => _position;
  Duration get duration => _duration;
  String? get error => _error;

  double get volume => _level;
  bool get muted => _level == 0;

  /// The slider: dragging to 0 mutes, anything above unmutes. The audible level is remembered for next time.
  void setVolume(double value) {
    _level = value.clamp(0.0, 1.0);
    if (_level > 0) {
      _lastAudible = _level;
      _volumeStore.write(_level);
    }
    _backend?.setVolume(_level);
    notifyListeners();
  }

  /// The speaker button: off when sound is on; back to the last audible level when it is off.
  void toggleMute() => setVolume(muted ? _lastAudible : 0);

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
      ..setSource(url)
      ..setVolume(volume);
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

  /// Jumps to [position] and plays from there (the play button of a transcript phrase).
  Future<void> seekAndPlay(Duration position) async {
    seek(position);
    if (!_playing) await togglePlay();
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
