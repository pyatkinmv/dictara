import 'volume_store_stub.dart' if (dart.library.js_interop) 'volume_store_web.dart' as impl;

/// Where the player's volume survives a page reload, so it is not set again on every recording. An interface so
/// tests can see or seed it without a browser.
abstract class VolumeStore {
  /// The last audible volume in 0..1, or null when none was saved.
  double? read();
  void write(double volume);
}

/// The browser's `localStorage`.
class BrowserVolumeStore implements VolumeStore {
  const BrowserVolumeStore();

  @override
  double? read() => impl.read();

  @override
  void write(double volume) => impl.write(volume);
}
