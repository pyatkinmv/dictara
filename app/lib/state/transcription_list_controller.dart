import 'package:flutter/foundation.dart';

import '../data/transcription_repository.dart';
import '../models/tag.dart';
import '../models/transcription.dart';

/// State of the transcriptions list screen: the query (search, filters, sort)
/// and the result. Lives above the router so filters survive navigation.
class TranscriptionListController extends ChangeNotifier {
  TranscriptionListController(this._repository) {
    _repository.tags().then((tags) {
      if (_disposed) return;
      _tags = tags;
      notifyListeners();
    });
    _load();
  }

  final TranscriptionRepository _repository;

  TranscriptionQuery _query = const TranscriptionQuery();
  List<Transcription> _items = const [];
  List<Tag> _tags = const [];
  bool _loading = true;
  int _requestId = 0;
  bool _disposed = false;

  TranscriptionQuery get query => _query;
  List<Transcription> get items => _items;

  /// The tag library, for the filter dropdown.
  List<Tag> get tags => _tags;

  /// True until the first result arrives.
  bool get loading => _loading;

  void setSearch(String value) => _apply(_query.copyWith(search: value));

  void setSort(TranscriptionSort sort) => _apply(_query.copyWith(sort: sort));

  void toggleStatus(TranscriptionStatus status) {
    _apply(_query.copyWith(statuses: _toggled(_query.statuses, status)));
  }

  void toggleTag(String label) {
    _apply(_query.copyWith(tags: _toggled(_query.tags, label)));
  }

  static Set<T> _toggled<T>(Set<T> set, T value) {
    return set.contains(value) ? ({...set}..remove(value)) : {...set, value};
  }

  void _apply(TranscriptionQuery query) {
    _query = query;
    notifyListeners(); // the controls reflect the new query right away
    _load();
  }

  Future<void> _load() async {
    final id = ++_requestId;
    final items = await _repository.list(_query);
    // A newer query was issued while this one was in flight.
    if (_disposed || id != _requestId) return;
    _items = items;
    _loading = false;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
