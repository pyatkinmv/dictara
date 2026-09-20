import '../models/speaker.dart';
import '../models/speaker_entry.dart';
import '../models/tag.dart';
import '../models/tag_entry.dart';
import '../models/transcription.dart';
import '../models/transcription_detail.dart';
import '../models/upload.dart';

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
  Future<TranscriptionDetail?> get(String id);

  /// Sends a file for transcription (`POST /transcriptions`) and returns the new
  /// item, which starts out `pending`. The server settings of the dialog
  /// (language, summary, speakers) travel with it.
  Future<Transcription> upload(UploadRequest request);

  /// Renames a transcription (`PATCH /jobs/{id}/title`).
  Future<void> rename(String id, String title);

  /// Puts the tag with this label on a transcription and returns it. If the
  /// library has no such tag yet it is created first (`POST /jobs/{id}/tags`
  /// with either a `tag_id` or a `label`, api-gaps §2.3).
  Future<Tag> addTag(String id, String label);

  /// Takes a tag off one transcription; it stays in the library
  /// (`DELETE /jobs/{id}/tags/{tagId}`).
  Future<void> removeTag(String id, String label);

  /// The tag library, in display order.
  Future<List<Tag>> tags();

  /// The tag library with descriptions and usage, for the Tags page
  /// (`GET /tags`, `docs/api-gaps.md` §2.1).
  Future<List<TagEntry>> tagLibrary();

  /// Creates a tag in the library (`POST /tags`); it gets the next palette color.
  Future<Tag> createTag(String label, String description);

  /// Renames and/or re-describes the tag called [label] (`PUT /tags/{id}`).
  /// Transcriptions that carry it follow the new name.
  Future<void> updateTag(String label, {required String newLabel, required String description});

  /// Deletes the tag from the library and from every transcription
  /// (`DELETE /tags/{id}`); returns how many transcriptions it was removed from.
  Future<int> deleteTag(String label);

  /// The speaker library, in creation order.
  Future<List<Speaker>> speakers();

  /// The speaker library with descriptions and usage, for the Speakers page
  /// (`GET /speakers`, `docs/api-gaps.md` §2.2).
  Future<List<SpeakerEntry>> speakerLibrary();

  /// Creates a speaker in the library (`POST /speakers`); they get the next
  /// palette color and keep the name's case.
  Future<Speaker> createSpeaker(String name, String description);

  /// Renames and/or re-describes the speaker called [name] (`PUT /speakers/{id}`).
  /// Transcriptions that use them follow the new name.
  Future<void> updateSpeaker(String name, {required String newName, required String description});

  /// Deletes the speaker from the library (`DELETE /speakers/{id}`); the slots
  /// and segments that were theirs go back to "Speaker N". Returns how many
  /// transcriptions they were removed from.
  Future<int> deleteSpeaker(String name);

  /// Attributes the given segments of a transcription to the speaker called
  /// [name] and returns that speaker. A name that is not in the library yet is
  /// created first.
  ///
  /// The gateway has nothing for this yet: `docs/api-gaps.md` §2.5 sketches
  /// `PUT /jobs/{id}/speakers/{slot}` with a scope of a whole slot or one
  /// segment, and §1.3 leaves the storage open. This mock works per segment,
  /// as the design does.
  Future<Speaker> assignSpeaker(String id, Set<int> segmentIndexes, String name);

  // AI suggestions (`docs/api-gaps.md` §2.4, still marked TBD there). Each call
  // resolves part of the suggestions of a transcription; the record disappears
  // once nothing is left. Read the outcome back with [get].

  /// Accepts everything that is left: the new title, every tag, every speaker.
  Future<void> acceptAllSuggestions(String id);

  /// Rejects everything that is left.
  Future<void> dismissAllSuggestions(String id);

  /// Adds the suggested tag to the transcription (and to the library if new).
  Future<void> acceptTagSuggestion(String id, String label);

  Future<void> rejectTagSuggestion(String id, String label);

  /// Confirms the suggested speaker for a diarizer slot (adding them to the
  /// library if new).
  Future<void> acceptSpeakerSuggestion(String id, int slot);

  Future<void> rejectSpeakerSuggestion(String id, int slot);
}

/// The items that match the search text, status and tag filters of [query];
/// ordering is left to the caller. Used where the source cannot filter itself.
List<Transcription> filterTranscriptions(Iterable<Transcription> items, TranscriptionQuery query) {
  final q = query.search.trim().toLowerCase();
  return [
    for (final i in items)
      if ((query.statuses.isEmpty || query.statuses.contains(i.status)) &&
          (query.tags.isEmpty || i.tags.any((t) => query.tags.contains(t.label))) &&
          (q.isEmpty || i.title.toLowerCase().contains(q)))
        i,
  ];
}
