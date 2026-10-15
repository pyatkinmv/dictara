import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/transcription_repository.dart';
import '../models/speaker.dart';
import '../models/tag.dart';
import '../models/transcription_detail.dart';

/// Keys of the suggestion actions, for [TranscriptionDetailController.isBusy].
abstract final class SuggestionAction {
  static const acceptAll = 'accept-all';
  static const dismissAll = 'dismiss-all';
  static String acceptTag(String label) => 'accept-tag:$label';
  static String rejectTag(String label) => 'reject-tag:$label';
  static String acceptSpeaker(int slot) => 'accept-speaker:$slot';
  static String rejectSpeaker(int slot) => 'reject-speaker:$slot';
}

/// The transcription that is open on the detail page. Lives above the router
/// so the topbar can show its title.
class TranscriptionDetailController extends ChangeNotifier {
  TranscriptionDetailController(
    this._repository, {
    VoidCallback? onChanged,
    this.regenerationPollInterval = const Duration(seconds: 3),
  }) : _onChanged = onChanged;

  /// How often an open transcription is read again while its summary is being regenerated.
  final Duration regenerationPollInterval;

  final TranscriptionRepository _repository;

  /// Called after a change that other screens (the list) must pick up.
  final VoidCallback? _onChanged;

  TranscriptionDetail? _current;
  List<Tag> _library = const [];
  List<Speaker> _speakers = const [];
  bool _notFound = false;
  int _requestId = 0;
  bool _disposed = false;
  Timer? _regenerationTimer;

  /// The open transcription; null while loading or when there is none.
  TranscriptionDetail? get current => _current;

  /// The tag library, for the "Add tag" dropdown.
  List<Tag> get library => _library;

  /// The speaker library, for the Speaker Picker.
  List<Speaker> get speakers => _speakers;

  /// The last requested id does not exist or is not done yet.
  bool get notFound => _notFound;

  Future<void> open(String id) async {
    if (_current?.item.id != id) {
      _regenerationTimer?.cancel();
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
    // Opened while a regeneration is running (started before, or in another tab): wait for it here too.
    _watchRegeneration(id);
  }

  /// Asks the AI again about the open transcription (once per transcription). The summary is replaced when it
  /// is done; meanwhile the old one stays and the page shows that it is running.
  Future<void> regenerateSummary() async {
    final current = _current;
    if (current == null || current.regeneration != SummaryRegeneration.available) return;
    final id = current.item.id;

    // A new attempt has no old complaint (the gateway forgets it as the attempt starts).
    _current = current.copyWith(regeneration: SummaryRegeneration.running, clearRegenerationError: true);
    notifyListeners();
    try {
      await _repository.regenerateSummary(id);
    } on Object {
      // Not started (already used, no service...): show what the gateway says is true now.
      final detail = await _repository.get(id);
      if (!_disposed && _current?.item.id == id && detail != null) {
        _current = detail;
        notifyListeners();
      }
      rethrow;
    }
    _watchRegeneration(id);
  }

  void _watchRegeneration(String id) {
    _regenerationTimer?.cancel();
    if (_disposed || _current?.item.id != id || _current?.regeneration != SummaryRegeneration.running) return;
    _regenerationTimer = Timer.periodic(regenerationPollInterval, (timer) async {
      final detail = await _repository.get(id);
      if (_disposed || _current?.item.id != id) {
        timer.cancel();
        return;
      }
      if (detail == null || detail.regeneration == SummaryRegeneration.running) return;
      timer.cancel();
      // The new summary, and new proposals (a title, tags, speakers) that the page shows.
      final library = await _repository.tags();
      final speakers = await _repository.speakers();
      if (_disposed || _current?.item.id != id) return;
      _current = detail;
      _library = library;
      _speakers = speakers;
      notifyListeners();
      _onChanged?.call();
    });
  }

  /// The open transcription's transcript as plain text; null when there is none.
  Future<String?> transcriptText() {
    final current = _current;
    return current == null ? Future.value() : _repository.transcriptText(current.item.id);
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

  // ── AI suggestions ─────────────────────────────────────────────────────────

  final Set<String> _busy = {};

  /// Whether the action behind [key] (see [SuggestionAction]) is in progress;
  /// its button shows a spinner and ignores clicks meanwhile.
  bool isBusy(String key) => _busy.contains(key);

  Future<void> acceptAllSuggestions() => _resolve(SuggestionAction.acceptAll, _repository.acceptAllSuggestions);

  Future<void> dismissAllSuggestions() => _resolve(SuggestionAction.dismissAll, _repository.dismissAllSuggestions);

  Future<void> acceptTagSuggestion(String label) =>
      _resolve(SuggestionAction.acceptTag(label), (id) => _repository.acceptTagSuggestion(id, label));

  Future<void> rejectTagSuggestion(String label) =>
      _resolve(SuggestionAction.rejectTag(label), (id) => _repository.rejectTagSuggestion(id, label));

  Future<void> acceptSpeakerSuggestion(int slot) =>
      _resolve(SuggestionAction.acceptSpeaker(slot), (id) => _repository.acceptSpeakerSuggestion(id, slot));

  Future<void> rejectSpeakerSuggestion(int slot) =>
      _resolve(SuggestionAction.rejectSpeaker(slot), (id) => _repository.rejectSpeakerSuggestion(id, slot));

  /// Runs one suggestion action, then re-reads the transcription (an accepted
  /// suggestion changes its title, tags and speakers) and the libraries.
  Future<void> _resolve(String key, Future<void> Function(String id) action) async {
    final current = _current;
    if (current == null || _busy.contains(key)) return;
    final id = current.item.id;

    _busy.add(key);
    notifyListeners();
    try {
      await action(id);
      final detail = await _repository.get(id);
      final library = await _repository.tags();
      final speakers = await _repository.speakers();
      // The user moved on to another transcription meanwhile.
      if (!_disposed && _current?.item.id == id) {
        _current = detail;
        _library = library;
        _speakers = speakers;
      }
    } finally {
      _busy.remove(key);
    }
    if (_disposed) return;
    notifyListeners();
    _onChanged?.call();
  }

  /// Attributes the given segments of the open transcription to the speaker
  /// called [name], creating the speaker in the library if it is new. With
  /// [allBySpeaker] the speaker's voice is settled for the whole transcription.
  ///
  /// What changed (a voice named, marks on segments dropped, a new speaker) is decided by the repository, so the
  /// transcription is read again instead of guessing the outcome here.
  Future<void> assignSpeaker(Set<int> segmentIndexes, String name, bool allBySpeaker) async {
    final current = _current;
    if (current == null || segmentIndexes.isEmpty || name.trim().isEmpty) return;

    await _repository.assignSpeaker(current.item.id, segmentIndexes, name, allBySpeaker: allBySpeaker);
    final detail = await _repository.get(current.item.id);
    final speakers = await _repository.speakers();
    // The user moved on to another transcription meanwhile.
    if (_disposed || _current?.item.id != current.item.id) return;
    _speakers = speakers;
    if (detail != null) _current = detail;
    notifyListeners();
    _onChanged?.call();
  }

  @override
  void dispose() {
    _disposed = true;
    _regenerationTimer?.cancel();
    super.dispose();
  }
}
