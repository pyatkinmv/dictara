import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'audio_backend.dart';

AudioBackend createAudioBackend({
  required void Function(Duration position) onPosition,
  required void Function(Duration duration) onDuration,
  required void Function() onEnded,
  required void Function(String message) onError,
}) => _BrowserAudioBackend(onPosition: onPosition, onDuration: onDuration, onEnded: onEnded, onError: onError);

class _BrowserAudioBackend implements AudioBackend {
  _BrowserAudioBackend({
    required void Function(Duration position) onPosition,
    required void Function(Duration duration) onDuration,
    required void Function() onEnded,
    required void Function(String message) onError,
  }) : _element = web.HTMLAudioElement()..preload = 'metadata' {
    _element.ontimeupdate = ((web.Event _) {
      onPosition(Duration(milliseconds: (_element.currentTime * 1000).round()));
    }).toJS;
    _element.onloadedmetadata = ((web.Event _) {
      final seconds = _element.duration;
      if (seconds.isFinite) onDuration(Duration(milliseconds: (seconds * 1000).round()));
    }).toJS;
    _element.onended = ((web.Event _) => onEnded()).toJS;
    // The element's own MediaError (bad/expired token, network trouble) has no useful detail worth surfacing.
    _element.onerror = ((web.Event _) => onError('Could not play this audio.')).toJS;
  }

  final web.HTMLAudioElement _element;

  @override
  void setSource(String url) => _element.src = url;

  @override
  Future<void> play() => _element.play().toDart.then((_) {});

  @override
  void pause() => _element.pause();

  @override
  void seek(Duration position) => _element.currentTime = position.inMilliseconds / 1000;

  @override
  void dispose() {
    _element.pause();
    _element.src = '';
  }
}
