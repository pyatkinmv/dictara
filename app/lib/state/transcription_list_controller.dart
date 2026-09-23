import 'dart:async';
import 'dart:math' show max, min;

import 'package:flutter/foundation.dart';

import '../data/transcription_repository.dart';
import '../models/speaker.dart';
import '../models/tag.dart';
import '../models/transcription.dart';
import '../models/upload.dart';
import 'auth_controller.dart';

/// State of the transcriptions list screen: the query (search, filters, sort)
/// and the result. Lives above the router so filters survive navigation.
class TranscriptionListController extends ChangeNotifier {
  /// [pollInterval]: while some transcription is still pending or processing,
  /// ask again this often, so progress moves without the user reloading. Null
  /// (the default) never polls.
  ///
  /// [pageSize] is how many transcriptions each request brings; the list grows a page at a time
  /// (see [loadMore]).
  ///
  /// [searchDebounce]: the search is done by the server, so a request is not sent for every letter typed
  /// but once the typing pauses this long (zero: at once).
  ///
  /// [auth]: this controller is built (and makes its first request) before the app knows whether anyone is
  /// signed in yet, so that request can go out without a session. Given one, a login re-runs the query —
  /// without it, a first request that failed (or, only locally, quietly answered as a different user through
  /// `LocalDevAuthFilter`) would otherwise sit on screen unrefreshed.
  TranscriptionListController(
    this._repository, {
    this.pollInterval,
    this.pageSize = transcriptionPageSize,
    this.searchDebounce = const Duration(milliseconds: 250),
    AuthController? auth,
  }) : _auth = auth {
    _auth?.addListener(_onAuthChanged);
    _loadTags();
    _loadSpeakers();
    _load();
  }

  final TranscriptionRepository _repository;
  final AuthController? _auth;

  void _onAuthChanged() {
    if (_auth!.isLoggedIn) reload();
  }
  final Duration? pollInterval;
  final int pageSize;
  final Duration searchDebounce;
  Timer? _poll;
  Timer? _searchTimer;

  TranscriptionQuery _query = const TranscriptionQuery();
  List<Transcription> _items = const [];
  List<Tag> _tags = const [];
  List<Speaker> _speakers = const [];
  bool _loading = true;
  bool _hasMore = false;
  bool _loadingMore = false;
  bool _loadMoreFailed = false;
  int _requestId = 0;
  bool _disposed = false;

  TranscriptionQuery get query => _query;
  List<Transcription> get items => _items;

  /// The tag library, for the filter dropdown.
  List<Tag> get tags => _tags;

  /// The speaker library, for the filter dropdown.
  List<Speaker> get speakers => _speakers;

  /// True until the first result arrives.
  bool get loading => _loading;

  /// Whether more transcriptions can be loaded after the ones in [items].
  bool get hasMore => _hasMore;

  /// A next page is being fetched.
  bool get loadingMore => _loadingMore;

  /// The last attempt to fetch a next page failed; [loadMore] with `retry: true` tries again.
  bool get loadMoreFailed => _loadMoreFailed;

  /// Re-runs the current query and refreshes the tag library, e.g. after
  /// something changed a transcription or created a tag.
  Future<void> reload() async {
    await _loadTags();
    await _loadSpeakers();
    // A tag or speaker that was deleted or renamed must not keep filtering the list.
    final knownTags = {for (final t in _tags) t.label};
    final knownSpeakers = {for (final s in _speakers) s.name};
    if (_query.tags.any((label) => !knownTags.contains(label)) ||
        _query.speakers.any((name) => !knownSpeakers.contains(name))) {
      _query = _query.copyWith(
        tags: {for (final label in _query.tags) if (knownTags.contains(label)) label},
        speakers: {for (final name in _query.speakers) if (knownSpeakers.contains(name)) name},
      );
    }
    await _load();
  }

  /// Sends a file for transcription. The list then shows everything again, so
  /// that the new item is not hidden by a search or filter left over.
  Future<UploadOutcome> upload(
    UploadRequest request, {
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  }) async {
    final outcome = await _repository.upload(request, onProgress: onProgress, cancellation: cancellation);
    _query = _query.copyWith(search: '', statuses: {}, tags: {}, speakers: {});
    notifyListeners();
    await _load(reset: true);
    return outcome;
  }

  Future<void> _loadTags() async {
    final tags = await _repository.tags();
    if (_disposed) return;
    _tags = tags;
    notifyListeners();
  }

  Future<void> _loadSpeakers() async {
    final speakers = await _repository.speakers();
    if (_disposed) return;
    _speakers = speakers;
    notifyListeners();
  }

  /// The text in the search field changed. The field shows it right away; the list follows after a pause.
  void setSearch(String value) {
    _query = _query.copyWith(search: value);
    notifyListeners();
    _searchTimer?.cancel();
    if (searchDebounce == Duration.zero) {
      _load(reset: true);
    } else {
      _searchTimer = Timer(searchDebounce, () => _load(reset: true));
    }
  }

  void setSort(TranscriptionSort sort) => _apply(_query.copyWith(sort: sort));

  void toggleStatus(TranscriptionStatus status) {
    _apply(_query.copyWith(statuses: _toggled(_query.statuses, status)));
  }

  void toggleTag(String label) {
    _apply(_query.copyWith(tags: _toggled(_query.tags, label)));
  }

  void toggleSpeaker(String name) {
    _apply(_query.copyWith(speakers: _toggled(_query.speakers, name)));
  }

  /// Replaces the query with just this one tag: every other filter (status, other tags/speakers, search)
  /// is cleared, for the "N more →" link on the Tags page — a fresh, single-criterion view of that tag.
  /// The sort order is left as it was, the same as [upload] leaves it when it resets the other filters.
  void filterByTag(String label) {
    _apply(_query.copyWith(search: '', statuses: {}, tags: {label}, speakers: {}));
  }

  /// The speaker counterpart of [filterByTag], for the Speakers page.
  void filterBySpeaker(String name) {
    _apply(_query.copyWith(search: '', statuses: {}, tags: {}, speakers: {name}));
  }

  static Set<T> _toggled<T>(Set<T> set, T value) {
    return set.contains(value) ? ({...set}..remove(value)) : {...set, value};
  }

  void _apply(TranscriptionQuery query) {
    _searchTimer?.cancel(); // this request carries the search text as well
    _query = query;
    notifyListeners(); // the controls reflect the new query right away
    _load(reset: true);
  }

  /// Fetches the list again. A new query ([reset]) starts over with the first page; otherwise the
  /// request covers everything loaded so far, so that a refresh (the poll, a rename) keeps what the
  /// user has scrolled through and picks up new items and progress in one go.
  Future<void> _load({bool reset = false}) async {
    final id = ++_requestId;
    final pages = reset ? 1 : max(1, (_items.length + pageSize - 1) ~/ pageSize);
    final size = min(pages * pageSize, transcriptionMaxPageSize);
    final page = await _repository.list(_query, page: 0, size: size);
    // A newer query was issued while this one was in flight.
    if (_disposed || id != _requestId) return;
    _items = page.items;
    _hasMore = page.hasNext;
    _loading = false;
    _loadingMore = false;
    _loadMoreFailed = false;
    notifyListeners();
    _schedulePoll();
  }

  /// Fetches the page after the loaded items and appends it (the list scrolls endlessly). Does nothing
  /// when there is no next page or one is on its way; after a failure it waits for [retry].
  Future<void> loadMore({bool retry = false}) async {
    if (!_hasMore || _loadingMore || _loading) return;
    if (_loadMoreFailed && !retry) return;
    final id = _requestId;
    _loadingMore = true;
    _loadMoreFailed = false;
    notifyListeners();
    try {
      final page = await _repository.list(_query, page: _items.length ~/ pageSize, size: pageSize);
      // A refresh or a new query replaced the list meanwhile; it owns the state now.
      if (_disposed || id != _requestId) return;
      // Pages are cut by position, so an upload in between can repeat the last item of the previous page.
      final known = {for (final item in _items) item.id};
      _items = [..._items, for (final item in page.items) if (!known.contains(item.id)) item];
      _hasMore = page.hasNext;
      _loadingMore = false;
      notifyListeners();
    } on Object {
      if (_disposed || id != _requestId) return;
      _loadingMore = false;
      _loadMoreFailed = true;
      notifyListeners();
    }
  }

  void _schedulePoll() {
    _poll?.cancel();
    _poll = null;
    final interval = pollInterval;
    // "failed" is a final status too, same as "done" — polling it forever would never change anything.
    if (interval == null || !_items.any((i) => i.status == TranscriptionStatus.pending || i.status == TranscriptionStatus.processing)) {
      return;
    }
    _poll = Timer(interval, _pollOnce);
  }

  /// A refresh by the clock. When it fails (the gateway is restarting, the network blinked) nobody asked for
  /// it, so the user is not bothered: it simply tries again at the next tick.
  Future<void> _pollOnce() async {
    try {
      await _load();
    } on Object {
      if (!_disposed) _schedulePoll();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _auth?.removeListener(_onAuthChanged);
    _poll?.cancel();
    _searchTimer?.cancel();
    super.dispose();
  }
}
