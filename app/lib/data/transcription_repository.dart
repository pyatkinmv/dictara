import '../models/speaker.dart';
import '../models/speaker_entry.dart';
import '../models/tag.dart';
import '../models/tag_entry.dart';
import '../models/transcription.dart';
import '../models/transcription_detail.dart';
import '../models/upload.dart';

enum TranscriptionSort { newest, oldest, longest, name }

/// How many transcriptions the list loads at a time.
const transcriptionPageSize = 30;

/// The most the gateway serves in one page (`GET /transcriptions?size=`).
const transcriptionMaxPageSize = 500;

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
  /// One page of the list for [query]: [size] items starting at item `page * size`
  /// (`GET /transcriptions?page=&size=`). To refresh what is already on screen ask for
  /// page 0 with a size that covers it.
  Future<TranscriptionPage> list(TranscriptionQuery query, {int page = 0, int size = transcriptionPageSize});

  /// A finished transcription with its transcript; null if there is no such
  /// transcription or it is not done yet.
  Future<TranscriptionDetail?> get(String id);

  /// The transcript as plain text, for the ".txt" and "Copy" buttons (`GET /transcript?jobId=`); null when
  /// there is none.
  Future<String?> transcriptText(String id);

  /// Sends a file for transcription (`POST /transcriptions`) and returns the entry it became, which starts out
  /// `pending`, or, when the user has uploaded this file with these options before, the existing one
  /// ([UploadOutcome.duplicate]): nothing is transcribed twice. The options of the dialog travel with it.
  /// [onProgress] gets the share of the file that has gone out; [cancellation] stops the upload.
  Future<UploadOutcome> upload(
    UploadRequest request, {
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  });

  /// Renames a transcription (`PATCH /jobs/{id}/title`).
  Future<void> rename(String id, String title);

  /// Puts the tag with this label on a transcription and returns it. If the
  /// library has no such tag yet it is created first (`POST /transcriptions/{id}/tags`
  /// with either a `tag_id` or a `label`).
  Future<Tag> addTag(String id, String label);

  /// Takes a tag off one transcription; it stays in the library
  /// (`DELETE /transcriptions/{id}/tags/{tagId}`).
  Future<void> removeTag(String id, String label);

  /// The tag library, in display order.
  Future<List<Tag>> tags();

  /// The tag library with descriptions and usage, for the Tags page
  /// (`GET /tags`).
  Future<TagLibrary> tagLibrary();

  /// Creates a tag in the library (`POST /tags`).
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
  /// created first (`PUT /transcriptions/{id}/speakers`).
  ///
  /// With [allBySpeaker] the segments are everything the user sees under one name and are all to become
  /// [name]: a diarizer voice that is entirely among them is settled for good in this transcription (the row of
  /// chips in the header names it too), the other segments are set one by one. Without it the segments are
  /// set one by one and the voices are left alone. Read the outcome back with [get].
  Future<Speaker> assignSpeaker(String id, Set<int> segmentIndexes, String name, {bool allBySpeaker = false});

  // AI suggestions (the gateway keeps them until the user settles them). Each call
  // resolves part of the suggestions of a transcription; the record disappears
  // once nothing is left. Read the outcome back with [get].

  /// Accepts everything that is left: the new title, every tag, every speaker.
  Future<void> acceptAllSuggestions(String id);

  /// Rejects everything that is left.
  Future<void> dismissAllSuggestions(String id);

  /// Adds the suggested tag to the transcription. The gateway proposes only tags of the library.
  Future<void> acceptTagSuggestion(String id, String label);

  Future<void> rejectTagSuggestion(String id, String label);

  /// Confirms the suggested speaker for a diarizer slot. A speaker who is not in the
  /// library yet is created now, not before: rejecting leaves no trace.
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

/// The [page]th page of [size] items of an already filtered and sorted [items];
/// for sources that cannot page by themselves.
TranscriptionPage sliceTranscriptions(List<Transcription> items, int page, int size) {
  final start = page * size;
  if (start >= items.length) return const TranscriptionPage([], hasNext: false);
  final end = start + size;
  return TranscriptionPage(items.sublist(start, end < items.length ? end : items.length), hasNext: end < items.length);
}
