import 'package:flutter/foundation.dart';

import '../data/transcription_repository.dart';
import '../models/speaker.dart';
import '../models/tag.dart';
import '../models/transcription_detail.dart';

/// The transcription that is open on the detail page. Lives above the router
/// so the topbar can show its title.
class TranscriptionDetailController extends ChangeNotifier {
  TranscriptionDetailController(this._repository, {VoidCallback? onChanged}) : _onChanged = onChanged;

  final TranscriptionRepository _repository;

  /// Called after a change that other screens (the list) must pick up.
  final VoidCallback? _onChanged;

  TranscriptionDetail? _current;
  List<Tag> _library = const [];
  List<Speaker> _speakers = const [];
  bool _notFound = false;
  int _requestId = 0;
  bool _disposed = false;

  /// The open transcription; null while loading or when there is none.
  TranscriptionDetail? get current => _current;

  /// The tag library, for the "Add tag" dropdown.
  List<Tag> get library => _library;

  /// The speaker library, for the Speaker Picker.
  List<Speaker> get speakers => _speakers;

  /// The last requested id does not exist or is not done yet.
  bool get notFound => _notFound;

  Future<void> open(int id) async {
    if (_current?.item.id != id) {
      _current = null;
      _notFound = false;
      notifyListeners();
    }
    final requestId = ++_requestId;
    final detail = await _repository.get(id);
    final library = await _repository.tags();
    final speakers = await _repository.speakers();
    // Another transcription was requested while this one was loading.
    if (_disposed || requestId != _requestId) return;
    _current = detail;
    _library = library;
    _speakers = speakers;
    _notFound = detail == null;
    notifyListeners();
  }

  /// Renames the open transcription. An empty title keeps the old one.
  Future<void> rename(String title) async {
    final current = _current;
    final trimmed = title.trim();
    if (current == null || trimmed.isEmpty || trimmed == current.item.title) return;

    // Optimistic: show the new title right away, like the design does.
    _current = current.copyWith(item: current.item.copyWith(title: trimmed));
    notifyListeners();
    await _repository.rename(current.item.id, trimmed);
    if (_disposed) return;
    _onChanged?.call();
  }

  /// Puts a tag on the open transcription, creating it in the library if it
  /// does not exist yet. Labels are lowercase slugs.
  Future<void> addTag(String label) async {
    final current = _current;
    final wanted = label.trim().toLowerCase();
    if (current == null || wanted.isEmpty || current.item.tags.any((t) => t.label == wanted)) return;

    final tag = await _repository.addTag(current.item.id, wanted);
    final library = await _repository.tags();
    // The user moved on to another transcription meanwhile.
    if (_disposed || _current?.item.id != current.item.id) return;
    _library = library;
    _current = _current!.copyWith(item: _current!.item.copyWith(tags: [..._current!.item.tags, tag]));
    notifyListeners();
    _onChanged?.call();
  }

  /// Takes a tag off the open transcription; it stays in the library.
  Future<void> removeTag(String label) async {
    final current = _current;
    if (current == null) return;

    // Optimistic, like renaming.
    final remaining = [
      for (final t in current.item.tags)
        if (t.label != label) t,
    ];
    _current = current.copyWith(item: current.item.copyWith(tags: remaining));
    notifyListeners();
    await _repository.removeTag(current.item.id, label);
    if (_disposed) return;
    _onChanged?.call();
  }

  /// Attributes the given segments of the open transcription to the speaker
  /// called [name], creating the speaker in the library if it is new.
  Future<void> assignSpeaker(Set<int> segmentIndexes, String name) async {
    final current = _current;
    if (current == null || segmentIndexes.isEmpty || name.trim().isEmpty) return;

    final speaker = await _repository.assignSpeaker(current.item.id, segmentIndexes, name);
    final speakers = await _repository.speakers();
    // The user moved on to another transcription meanwhile.
    if (_disposed || _current?.item.id != current.item.id) return;
    _speakers = speakers;
    _current = _current!.copyWith(
      segmentSpeakers: {..._current!.segmentSpeakers, for (final i in segmentIndexes) i: speaker},
    );
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
