import '../models/speaker.dart';
import '../models/tag.dart';
import '../models/transcription.dart';
import '../models/transcription_detail.dart';

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

  /// A finished transcription with its transcript; null if there is no such
  /// transcription or it is not done yet.
  Future<TranscriptionDetail?> get(int id);

  /// Renames a transcription (`PATCH /jobs/{id}/title`).
  Future<void> rename(int id, String title);

  /// Puts the tag with this label on a transcription and returns it. If the
  /// library has no such tag yet it is created first (`POST /jobs/{id}/tags`
  /// with either a `tag_id` or a `label`, api-gaps §2.3).
  Future<Tag> addTag(int id, String label);

  /// Takes a tag off one transcription; it stays in the library
  /// (`DELETE /jobs/{id}/tags/{tagId}`).
  Future<void> removeTag(int id, String label);

  /// The tag library, in display order.
  Future<List<Tag>> tags();

  /// The speaker library, in creation order.
  Future<List<Speaker>> speakers();

  /// Attributes the given segments of a transcription to the speaker called
  /// [name] and returns that speaker. A name that is not in the library yet is
  /// created first.
  ///
  /// The gateway has nothing for this yet: `docs/api-gaps.md` §2.5 sketches
  /// `PUT /jobs/{id}/speakers/{slot}` with a scope of a whole slot or one
  /// segment, and §1.3 leaves the storage open. This mock works per segment,
  /// as the design does.
  Future<Speaker> assignSpeaker(int id, Set<int> segmentIndexes, String name);
}
