import 'audio_backend_stub.dart' if (dart.library.js_interop) 'audio_backend_web.dart' as impl;

/// Talks to whatever the browser uses to actually play audio — an interface so `AudioPlayerController` can
/// be tested without a real `<audio>` element. One backend per player; call [dispose] when done with it.
abstract class AudioBackend {
  void setSource(String url);
  Future<void> play();
  void pause();
  void seek(Duration position);
  void dispose();
}

/// The browser's own `<audio>` element (`BrowserAudioBackend` in the web-only implementation). [onPosition]
/// fires while playing (and once right after a seek); [onDuration] once metadata loads; [onEnded] when
/// playback reaches the end; [onError] if the element itself reports one — e.g. the playback token expired
/// and the server answered something that is not audio.
AudioBackend createAudioBackend({
  required void Function(Duration position) onPosition,
  required void Function(Duration duration) onDuration,
  required void Function() onEnded,
  required void Function(String message) onError,
}) => impl.createAudioBackend(onPosition: onPosition, onDuration: onDuration, onEnded: onEnded, onError: onError);
