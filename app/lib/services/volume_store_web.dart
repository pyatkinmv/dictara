import 'package:web/web.dart' as web;

const _key = 'dictara_audio_volume';

/// A blocked or unavailable `localStorage` (a private window, cleared site data) just means the volume is not
/// remembered, never a crash.
double? read() {
  try {
    final value = double.tryParse(web.window.localStorage.getItem(_key) ?? '');
    return value?.clamp(0.0, 1.0);
  } catch (_) {
    return null;
  }
}

void write(double volume) {
  try {
    web.window.localStorage.setItem(_key, volume.toString());
  } catch (_) {}
}
