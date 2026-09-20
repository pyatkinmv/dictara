import '../models/tag.dart';
import '../models/transcription.dart';

enum TranscriptionSort { newest, oldest, longest, name }

/// Server-side filtering/sorting of the list, shaped after `GET /transcriptions`
/// in `docs/api-gaps.md` (§3.1): `sort`, `status`, `tag_ids`, `q`.
class TranscriptionQuery {
  const TranscriptionQuery({
    this.search = '',
    this.statuses = const {},
    this.tags = const {},
    this.sort = TranscriptionSort.newest,
  });

  final String search;

  /// Any of; empty = all.
  final Set<TranscriptionStatus> statuses;

  /// Any of (by label); empty = all.
  final Set<String> tags;
  final TranscriptionSort sort;

  int get activeFilterCount => statuses.length + tags.length;

  TranscriptionQuery copyWith({
    String? search,
    Set<TranscriptionStatus>? statuses,
    Set<String>? tags,
    TranscriptionSort? sort,
  }) {
    return TranscriptionQuery(
      search: search ?? this.search,
      statuses: statuses ?? this.statuses,
      tags: tags ?? this.tags,
      sort: sort ?? this.sort,
    );
  }
}

/// Where screens get their data. The mock implementation is used until the
/// gateway grows the endpoints listed in `docs/api-gaps.md`.
abstract class TranscriptionRepository {
  Future<List<Transcription>> list(TranscriptionQuery query);

  /// The tag library, in display order.
  Future<List<Tag>> tags();
}
