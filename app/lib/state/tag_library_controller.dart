import 'package:flutter/foundation.dart';

import '../data/transcription_repository.dart';
import '../models/tag_entry.dart';

/// State of the Tags page: the library with descriptions and usage.
class TagLibraryController extends ChangeNotifier {
  TagLibraryController(this._repository, {VoidCallback? onChanged}) : _onChanged = onChanged;

  final TranscriptionRepository _repository;

  /// Called after the library changed, so other screens (the list's filter)
  /// pick it up.
  final VoidCallback? _onChanged;

  List<TagEntry> _entries = const [];
  int _tagged = 0;
  bool _loaded = false;
  bool _disposed = false;

  List<TagEntry> get entries => _entries;

  /// False until the library has been fetched once.
  bool get loaded => _loaded;

  /// How many distinct transcriptions carry at least one tag.
  int get taggedTranscriptions => _tagged;

  /// Tags are matched case-insensitively. [except] is the tag being edited.
  bool labelTaken(String label, {String? except}) {
    final wanted = label.trim().toLowerCase();
    return _entries.any((e) => e.tag.label == wanted && e.tag.label != except);
  }

  Future<void> load() async {
    final library = await _repository.tagLibrary();
    if (_disposed) return;
    _entries = library.entries;
    _tagged = library.taggedTranscriptions;
    _loaded = true;
    notifyListeners();
  }

  Future<void> create(String label, String description) async {
    await _repository.createTag(label, description);
    await _changed();
  }

  Future<void> update(String label, {required String newLabel, required String description}) async {
    await _repository.updateTag(label, newLabel: newLabel, description: description);
    await _changed();
  }

  /// Returns how many transcriptions the tag was removed from.
  Future<int> delete(String label) async {
    final affected = await _repository.deleteTag(label);
    await _changed();
    return affected;
  }

  Future<void> _changed() async {
    await load();
    if (!_disposed) _onChanged?.call();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
