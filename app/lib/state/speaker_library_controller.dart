import 'package:flutter/foundation.dart';

import '../data/transcription_repository.dart';
import '../models/speaker_entry.dart';

/// State of the Speakers page: the library with descriptions and usage.
class SpeakerLibraryController extends ChangeNotifier {
  SpeakerLibraryController(this._repository);

  final TranscriptionRepository _repository;

  List<SpeakerEntry> _entries = const [];
  bool _loaded = false;
  bool _disposed = false;

  List<SpeakerEntry> get entries => _entries;

  /// False until the library has been fetched once.
  bool get loaded => _loaded;

  /// Speakers are matched case-insensitively. [except] is the speaker being edited.
  bool nameTaken(String name, {String? except}) {
    final wanted = name.trim().toLowerCase();
    return _entries.any(
      (e) => e.speaker.name.toLowerCase() == wanted && e.speaker.name != except,
    );
  }

  Future<void> load() async {
    final entries = await _repository.speakerLibrary();
    if (_disposed) return;
    _entries = entries;
    _loaded = true;
    notifyListeners();
  }

  Future<void> create(String name, String description) async {
    await _repository.createSpeaker(name, description);
    await load();
  }

  Future<void> update(
    String name, {
    required String newName,
    required String description,
  }) async {
    await _repository.updateSpeaker(
      name,
      newName: newName,
      description: description,
    );
    await load();
  }

  /// Returns how many transcriptions the speaker was removed from.
  Future<int> delete(String name) async {
    final affected = await _repository.deleteSpeaker(name);
    await load();
    return affected;
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
