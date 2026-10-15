import 'package:dictara_web/services/volume_store.dart';

/// A [VolumeStore] that lives only in memory, for tests.
class InMemoryVolumeStore implements VolumeStore {
  InMemoryVolumeStore([this.saved]);

  double? saved;

  @override
  double? read() => saved;

  @override
  void write(double volume) => saved = volume;
}
