import 'audio_backend.dart';

AudioBackend createAudioBackend({
  required void Function(Duration position) onPosition,
  required void Function(Duration duration) onDuration,
  required void Function() onEnded,
  required void Function(String message) onError,
}) => throw UnsupportedError('Audio playback needs a browser.');
