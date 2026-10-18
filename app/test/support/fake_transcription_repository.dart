import 'dart:ui' show Color;

import 'package:dictara_web/models/speaker.dart';
import 'package:dictara_web/models/speaker_entry.dart';
import 'package:dictara_web/models/suggestions.dart';
import 'package:dictara_web/models/tag.dart';
import 'package:dictara_web/models/tag_entry.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/transcription_detail.dart';
import 'package:dictara_web/models/transcription_link.dart';
import 'package:dictara_web/models/upload.dart';
import 'package:dictara_web/util/format.dart';
import 'package:dictara_web/data/transcription_repository.dart';

// Mock data copied from `designs/1.9/cards-teal.html`.
const _meeting = Tag('meeting', Color(0xFF2563EB));
const _quarterly = Tag('quarterly', Color(0xFF059669));
const _interview = Tag('interview', Color(0xFF7C3AED));
const _hiring = Tag('hiring', Color(0xFFD97706));
const _ideas = Tag('ideas', Color(0xFF0891B2));
const _standup = Tag('standup', Color(0xFF6366F1));

const _seedTags = <Tag>[_meeting, _quarterly, _interview, _hiring, _ideas, _standup];

const _alexei = Speaker('Alexei Sorokin', Color(0xFF0D9488));
const _maria = Speaker('Maria Chen', Color(0xFF7C3AED));
const _james = Speaker('James Wright', Color(0xFFD97706));

/// The design shows the same transcript for every finished item.
const _transcript = <Segment>[
  Segment(
    start: Duration.zero,
    speakerSlot: 1,
    text: "Good morning everyone. Let's get started — we have a lot to cover today. "
        'The main focus is Q2 performance and planning the roadmap for the next quarter.',
  ),
  Segment(
    start: Duration(seconds: 34),
    speakerSlot: 2,
    text: 'Right. So Q2 was solid overall — revenue is up 18% quarter over quarter. '
        'The enterprise segment grew faster than expected, mostly driven by the new '
        'integrations we launched in May.',
  ),
  Segment(
    start: Duration(minutes: 1, seconds: 12),
    speakerSlot: 3,
    text: 'On the product side, we shipped 12 features versus the 9 we planned, but we also '
        'accumulated some technical debt in the payments module that we need to address in Q3.',
  ),
  Segment(
    start: Duration(minutes: 2, seconds: 5),
    speakerSlot: 1,
    text: "That's great to hear on the feature side. What's the plan for Q3? Are we maintaining "
        'the same pace or scaling up the team?',
  ),
  Segment(
    start: Duration(minutes: 2, seconds: 28),
    speakerSlot: 2,
    text: "We're planning to hire 4 more engineers by September, which will let us run two "
        'parallel development tracks. That should improve throughput significantly without '
        'sacrificing quality.',
  ),
  Segment(
    start: Duration(minutes: 3, seconds: 10),
    speakerSlot: 3,
    text: 'And the technical debt? How much time are we allocating to paying that down before '
        'we start adding more features on top?',
  ),
  Segment(
    start: Duration(minutes: 3, seconds: 30),
    speakerSlot: 2,
    text: "We're reserving 20% of engineering capacity in July specifically for the payments "
        "refactor. It's not glamorous but it's necessary — otherwise we risk reliability "
        'issues in the fall when volume spikes.',
  ),
  Segment(
    start: Duration(minutes: 4, seconds: 5),
    speakerSlot: 1,
    text: "Makes sense. Alright, let's move on to marketing. What's the Q3 strategy looking like?",
  ),
  Segment(
    start: Duration(minutes: 4, seconds: 22),
    speakerSlot: 3,
    text: "We're doubling down on content marketing — SEO-driven articles, a revamped case study "
        'library, and a monthly webinar series starting in August. These should bring in '
        "higher-intent leads than the paid campaigns we've relied on.",
  ),
  Segment(
    start: Duration(minutes: 5, seconds: 8),
    speakerSlot: 2,
    text: 'I like the webinar idea. Can we make sure sales is looped in from the start? '
        "They should be co-presenting, not just watching.",
  ),
];

/// The summary as the summarizer writes it: Markdown, with the key points and action items in it.
const _summary = '''Q2 closed strong: revenue up 18% QoQ, 12 features shipped vs. 9 planned. Heading into Q3, the team will hire 4 engineers, reserve 20% of capacity for a payments refactor, and pivot marketing to content-driven channels with a monthly webinar series launching in August.

**Key points**
* Revenue up 18% QoQ — enterprise growth and May integrations as primary drivers
* 12 features shipped vs. 9 planned; technical debt in payments module flagged for Q3
* 4 engineers to be hired by September for parallel development tracks
* Marketing pivoting to SEO content, case studies, and monthly webinars from August

**Action items**
* Reserve 20% engineering capacity in July for payments refactor
* Begin recruitment for 4 engineering roles — target: September
* Sales team to co-present webinar series starting August''';

/// Slots 1-3 mapped to people, for every item except the one that carries AI
/// suggestions in the design (id 6); its slots stay "Speaker N" until the
/// suggestions are accepted (iteration 7).
const _confirmedSpeakers = <int, Speaker>{1: _alexei, 2: _maria, 3: _james};
const _itemWithSuggestions = '6';

/// What the design's AI proposes for the item that has suggestions.
const _seedSuggestions = Suggestions(
  title: 'Product Sync — Q3 Roadmap',
  tags: [
    Tag('meeting', Color(0xFF2563EB)),
    Tag('product', Color(0xFF0891B2)),
    Tag('roadmap', Color(0xFF059669)),
  ],
  speakers: {1: _alexei, 2: _maria, 3: _james},
);

/// The year of the demo data is the current one, so that its dates are shown without a year (as the app does
/// for this year) whatever year the tests run in; a recording made in the year before shows its year.
final _year = DateTime.now().year;

List<Transcription> _seedItems() => [
      Transcription(
        id: '0',
        title: 'Quarterly Review Meeting',
        duration: const Duration(minutes: 14, seconds: 22),
        uploadedAt: DateTime(_year, 6, 22, 14, 41),
        recordedAt: DateTime(_year, 6, 20, 9, 12),
        status: TranscriptionStatus.done,
        tags: const [_meeting, _quarterly],
        speakerCount: 3,
      ),
      Transcription(
        id: '1',
        title: 'Economics Lecture — Part 2',
        duration: const Duration(minutes: 72),
        uploadedAt: DateTime(_year, 6, 22, 11, 5),
        status: TranscriptionStatus.processing,
        processingPct: 41,
      ),
      Transcription(
        id: '2',
        title: 'Interview: Anna K.',
        duration: const Duration(minutes: 47),
        uploadedAt: DateTime(_year, 6, 21, 16, 20),
        recordedAt: DateTime(_year - 1, 3, 4, 10, 30),
        status: TranscriptionStatus.done,
        tags: const [_interview, _hiring],
        speakerCount: 2,
      ),
      Transcription(
        id: '3',
        title: 'Podcast Episode #47',
        duration: const Duration(minutes: 38),
        uploadedAt: DateTime(_year, 6, 22, 9, 30),
        status: TranscriptionStatus.pending,
      ),
      Transcription(
        id: '4',
        title: 'Product Ideas — Voice Memo',
        duration: const Duration(minutes: 12),
        uploadedAt: DateTime(_year, 6, 20, 18, 2),
        status: TranscriptionStatus.done,
        tags: const [_ideas],
      ),
      Transcription(
        id: '5',
        title: 'Daily Standup 19 Jun',
        duration: const Duration(minutes: 8),
        uploadedAt: DateTime(_year, 6, 19, 10, 0),
        status: TranscriptionStatus.done,
        tags: const [_meeting, _standup],
        speakerCount: 3,
      ),
      Transcription(
        id: '6',
        title: 'product_sync_2025_06_23.mp4',
        duration: const Duration(minutes: 34),
        uploadedAt: DateTime(_year, 6, 23, 13, 15),
        status: TranscriptionStatus.done,
        speakerCount: 3,
      ),
    ];

/// How long an uploaded file waits and is processed in the mock.
class JobTiming {
  const JobTiming({required this.queue, required this.processing});

  /// Time spent `pending` before the transcriber picks the file up.
  final Duration queue;

  /// Time spent `processing`, until the transcription is `done`.
  final Duration processing;
}

/// An upload the mock is still working on.
class _Job {
  _Job(this.startedAt, this.speakers);

  final DateTime startedAt;

  /// Speakers found once it is done.
  final int speakers;
}

/// The last id of the design's own transcriptions; later ones are uploads.
const _lastSeedId = 6;

class FakeTranscriptionRepository implements TranscriptionRepository {
  /// [latency] makes the actions take a while, like a server would; the app
  /// uses it so the spinners of the design can be seen, tests do not.
  ///
  /// [jobTiming] makes uploads move on from `pending` through `processing` to
  /// `done` as time passes (judged by [clock] whenever data is asked for). Without
  /// it an upload stays `pending` forever, which keeps tests deterministic.
  ///
  /// [hasAudio]: false by default, so [audioUrl] answers null and the detail page never reaches for the real
  /// (browser-only) audio backend — tests that exercise the player construct their own `AudioPlayerController`
  /// directly with a fake backend instead of going through the full app for that.
  FakeTranscriptionRepository({this.latency, this.jobTiming, DateTime Function()? clock, this.hasAudio = false, this.audioGone = false, this.regenerationLimit = 1})
      : _clock = clock ?? DateTime.now;

  final Duration Function()? latency;
  final JobTiming? jobTiming;
  final DateTime Function() _clock;
  final bool hasAudio;

  /// The recording has audio on record but its file is gone: [audioUrl] throws [AudioUnavailableException].
  final bool audioGone;

  /// Uploads that have not finished yet, by transcription id.
  final Map<String, _Job> _jobs = {};

  /// Moves uploads along their `pending` → `processing` → `done` path.
  void _advanceJobs() {
    final timing = jobTiming;
    if (timing == null || _jobs.isEmpty) return;
    for (final id in _jobs.keys.toList()) {
      final job = _jobs[id]!;
      final index = _items.indexWhere((i) => i.id == id);
      if (index < 0) {
        _jobs.remove(id);
        continue;
      }
      final elapsed = _clock().difference(job.startedAt);
      final old = _items[index];
      final TranscriptionStatus status;
      int? pct;
      if (elapsed < timing.queue) {
        status = TranscriptionStatus.pending;
      } else if (elapsed < timing.queue + timing.processing) {
        status = TranscriptionStatus.processing;
        final done = elapsed - timing.queue;
        pct = (done.inMilliseconds * 100 ~/ timing.processing.inMilliseconds).clamp(0, 99);
      } else {
        status = TranscriptionStatus.done;
        _jobs.remove(id);
      }
      _items[index] = Transcription(
        id: old.id,
        title: old.title,
        duration: old.duration,
        uploadedAt: old.uploadedAt,
        status: status,
        processingPct: pct,
        tags: old.tags,
        speakerCount: status == TranscriptionStatus.done ? job.speakers : 0,
      );
    }
  }

  @override
  Future<UploadOutcome> upload(
    UploadRequest request, {
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  }) async {
    onProgress?.call(0.5);
    await _wait();
    final id = '${_items.fold(-1, (max, i) => _order(i) > max ? _order(i) : max) + 1}';
    // No real audio to measure: about a minute per megabyte, at least one.
    final minutes = (request.file.sizeBytes / (1024 * 1024)).ceil().clamp(1, 180);
    final item = Transcription(
      id: id,
      title: request.file.name,
      duration: Duration(minutes: minutes),
      uploadedAt: _clock(),
      status: TranscriptionStatus.pending,
    );
    _items.add(item);
    _jobs[id] = _Job(_clock(), request.speakers.expected ?? 2);
    onProgress?.call(1);
    return UploadOutcome(item);
  }

  Future<void> _wait() async {
    final delay = latency?.call();
    if (delay != null) await Future<void>.delayed(delay);
  }

  /// In creation order: a higher id was created later. Mutable so that
  /// renaming shows up everywhere, like a real backend.
  final List<Transcription> _items = _seedItems();

  /// The tag library, in creation order. Mutable: tags can be created from a transcription.
  final List<Tag> _library = List.of(_seedTags);

  /// What each tag is for, by label (from the design's Tags page).
  final Map<String, String> _tagDescriptions = {
    'meeting': 'Regular team meetings, standups, and group discussions.',
    'quarterly': 'End-of-quarter review sessions covering performance and roadmap.',
    'interview': 'Candidate interviews for open positions.',
    'hiring': 'Recruitment-related recordings including screens and debriefs.',
    'ideas': 'Brainstorming sessions and exploratory discussions.',
    'standup': 'Daily standup recordings. Short format, multiple speakers.',
  };

  /// The speaker library, in creation order. Mutable like the tag library.
  final List<Speaker> _speakerLibrary = [_alexei, _maria, _james];

  /// Who each speaker is, by name (from the design's Speakers page).
  final Map<String, String> _speakerDescriptions = {
    'Alexei Sorokin': 'Product lead. Discusses roadmap, feature prioritization, and team updates.',
    'Maria Chen': 'Head of Engineering. Covers architecture, sprints, and code quality.',
    'James Wright': 'Sales & Marketing lead. Focuses on customer acquisition and GTM strategy.',
  };

  /// Speakers assigned by hand, per transcription id, then per segment index.
  final Map<String, Map<int, Speaker>> _segmentSpeakers = {};

  /// Confirmed slot → speaker mapping per transcription id.
  final Map<String, Map<int, Speaker>> _slotSpeakers = {};

  /// The design's own transcriptions have everyone confirmed (bar the one with
  /// suggestions); a new upload starts with "Speaker N" everywhere.
  Map<int, Speaker> _slotsOf(String id) => _slotSpeakers.putIfAbsent(
        id,
        () => id == _itemWithSuggestions || (int.tryParse(id) ?? _lastSeedId + 1) > _lastSeedId ? {} : {..._confirmedSpeakers},
      );

  /// Suggestions still waiting for the user, per transcription id.
  final Map<String, Suggestions> _suggestions = {_itemWithSuggestions: _seedSuggestions};

  /// The mock hands out numeric ids (creation order), which the server would not.
  int _order(Transcription item) => int.parse(item.id);

  DateTime _recordedOrUploaded(Transcription item) => item.recordedAt ?? item.uploadedAt;

  /// Who actually speaks in transcription [id] right now, the same resolution `presentSpeakers` uses on the
  /// detail page (a phrase's own mark, else its voice's confirmed speaker, else its "Speaker N" placeholder,
  /// which can never match a filter since it is not a library name). Empty before there is a transcript to
  /// read at all — same as `get()`, and as the real gateway's `LEFT JOIN transcripts`.
  Set<String> _effectiveSpeakerNamesOf(String id) {
    final item = _items.firstWhere((i) => i.id == id);
    if (item.status != TranscriptionStatus.done) return const {};
    return TranscriptionDetail(
      item: item,
      segments: _transcript,
      speakers: _slotsOf(id),
      segmentSpeakers: _segmentSpeakers[id] ?? const {},
      originalFilename: '',
      fileSizeBytes: 0,
    ).presentSpeakers.map((s) => s.name).toSet();
  }

  @override
  Future<TranscriptionPage> list(TranscriptionQuery query, {int page = 0, int size = transcriptionPageSize}) async {
    _advanceJobs();
    var items = filterTranscriptions(_items, query);
    if (query.speakers.isNotEmpty) {
      items = [for (final i in items) if (_effectiveSpeakerNamesOf(i.id).any(query.speakers.contains)) i];
    }

    switch (query.sort) {
      case TranscriptionSort.newest:
        items.sort((a, b) => _order(b).compareTo(_order(a)));
      case TranscriptionSort.oldest:
        items.sort((a, b) => _order(a).compareTo(_order(b)));
      case TranscriptionSort.recordedNewest:
        items.sort((a, b) => _recordedOrUploaded(b).compareTo(_recordedOrUploaded(a)));
      case TranscriptionSort.recordedOldest:
        items.sort((a, b) => _recordedOrUploaded(a).compareTo(_recordedOrUploaded(b)));
      case TranscriptionSort.longest:
        items.sort((a, b) => (b.duration ?? Duration.zero).compareTo(a.duration ?? Duration.zero));
      case TranscriptionSort.name:
        items.sort((a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()));
    }
    return sliceTranscriptions(items, page, size);
  }

  @override
  Future<TranscriptionDetail?> get(String id) async {
    _advanceJobs();
    final index = _items.indexWhere((i) => i.id == id);
    if (index < 0 || _items[index].status != TranscriptionStatus.done) return null;
    return TranscriptionDetail(
      item: _items[index],
      segments: _transcript,
      speakers: Map.unmodifiable(_slotsOf(id)),
      segmentSpeakers: Map.unmodifiable(_segmentSpeakers[id] ?? const {}),
      suggestions: _suggestions[id],
      // The design shows the same summary and file details for every item.
      summary: _summaries[id] ?? _summary,
      regeneration: regenerationLimit == 0
          ? SummaryRegeneration.unavailable // the plan has none
          : _regenerating.contains(id)
          ? SummaryRegeneration.running
          : (regenerationLimit != null && (_regenerationsUsed[id] ?? 0) >= regenerationLimit!)
              ? SummaryRegeneration.used
              : SummaryRegeneration.available,
      regeneratedAt: _regeneratedAt[id],
      regenerationsUsed: _regenerationsUsed[id] ?? 0,
      regenerationsMax: regenerationLimit,
      regenerationError: _regenerationErrors[id],
      originalFilename: 'product_sync_2025_06_23.mp4',
      fileSizeBytes: 23488102, // 22.4 MB
      language: 'EN',
      wordCount: 1840,
    );
  }

  @override
  Future<String?> transcriptText(String id) async {
    final detail = await get(id);
    if (detail == null) return null;
    return [
      for (var i = 0; i < detail.segments.length; i++)
        '[${formatTimestamp(detail.segments[i].start)}] ${detail.speakerOfSegment(i)?.name ?? ''}: ${detail.segments[i].text}',
    ].join('\n');
  }

  @override
  Future<String?> audioUrl(String id) async {
    if (audioGone) throw const AudioUnavailableException();
    if (!hasAudio) return null;
    final detail = await get(id);
    if (detail == null) return null;
    return 'fake://audio/$id.mp3';
  }

  @override
  Future<void> rename(String id, String title) async {
    final index = _items.indexWhere((i) => i.id == id);
    if (index >= 0) _items[index] = _items[index].copyWith(title: title);
  }

  @override
  Future<List<Tag>> tags() async => List.unmodifiable(_library);

  @override
  Future<TagLibrary> tagLibrary() async => TagLibrary(
        entries: [
          for (final tag in _library)
            () {
              // Items are kept oldest first; the gateway sends only the two most recent, newest first, with
              // the true count separate — mirrored here so a tag used more than twice is testable.
              final all = [for (final item in _items.reversed) if (item.tags.contains(tag)) item];
              final matching = [for (final item in all) if (item.status == TranscriptionStatus.done) TranscriptionLink(id: item.id, title: item.title)];
              return TagEntry(
                tag: tag,
                description: _tagDescriptions[tag.label] ?? '',
                transcriptions: matching.take(2).toList(),
                count: all.length,
              );
            }(),
        ],
        taggedTranscriptions: _items.where((item) => item.tags.isNotEmpty).length,
      );

  @override
  Future<Tag> createTag(String label, String description) async {
    final wanted = label.trim().toLowerCase();
    final tag = _libraryTag(wanted) ??
        _createTag(Tag(wanted, libraryPalette[_library.length % libraryPalette.length]));
    _tagDescriptions[wanted] = description.trim();
    return tag;
  }

  @override
  Future<void> updateTag(String label, {required String newLabel, required String description}) async {
    final index = _library.indexWhere((t) => t.label == label);
    if (index < 0) return;
    final wanted = newLabel.trim().toLowerCase();
    final old = _library[index];
    final renamed = Tag(wanted, old.color);

    _library[index] = renamed;
    _tagDescriptions
      ..remove(label)
      ..[wanted] = description.trim();
    // Transcriptions follow the rename.
    for (var i = 0; i < _items.length; i++) {
      if (_items[i].tags.contains(old)) {
        _items[i] = _items[i].copyWith(tags: [for (final t in _items[i].tags) t == old ? renamed : t]);
      }
    }
  }

  @override
  Future<int> deleteTag(String label) async {
    final tag = _libraryTag(label);
    if (tag == null) return 0;
    var affected = 0;
    for (var i = 0; i < _items.length; i++) {
      if (_items[i].tags.contains(tag)) {
        affected++;
        _items[i] = _items[i].copyWith(tags: [for (final t in _items[i].tags) if (t != tag) t]);
      }
    }
    _library.remove(tag);
    _tagDescriptions.remove(label);
    return affected;
  }

  @override
  Future<List<Speaker>> speakers() async => List.unmodifiable(_speakerLibrary);

  @override
  Future<List<SpeakerEntry>> speakerLibrary() async {
    _advanceJobs();
    return [
      for (final speaker in _speakerLibrary)
        () {
          // Items are kept oldest first; the gateway sends only the two most recent, newest first, with the
          // true count separate — mirrored here so a speaker used more than twice is testable.
          final items = [for (final item in _items) if (_takesPart(item, speaker)) item];
          return SpeakerEntry(
            speaker: speaker,
            description: _speakerDescriptions[speaker.name] ?? '',
            transcriptions: [
              for (final item in items.reversed)
                if (item.status == TranscriptionStatus.done) TranscriptionLink(id: item.id, title: item.title),
            ].take(2).toList(),
            count: items.length,
            totalDuration: items.fold(Duration.zero, (sum, item) => sum + (item.duration ?? Duration.zero)),
          );
        }(),
    ];
  }

  @override
  Future<Speaker> createSpeaker(String name, String description) async {
    final wanted = name.trim();
    var speaker = _speakerNamed(wanted);
    if (speaker == null) {
      speaker = Speaker(wanted, libraryPalette[_speakerLibrary.length % libraryPalette.length]);
      _speakerLibrary.add(speaker);
    }
    _speakerDescriptions[speaker.name] = description.trim();
    return speaker;
  }

  @override
  Future<void> updateSpeaker(String name, {required String newName, required String description}) async {
    final index = _speakerLibrary.indexWhere((s) => s.name == name);
    if (index < 0) return;
    final old = _speakerLibrary[index];
    final renamed = Speaker(newName.trim(), old.color);
    _speakerLibrary[index] = renamed;
    _speakerDescriptions
      ..remove(name)
      ..[renamed.name] = description.trim();
    _replaceSpeaker(old, renamed);
  }

  @override
  Future<int> deleteSpeaker(String name) async {
    final speaker = _speakerLibrary.where((s) => s.name == name).firstOrNull;
    if (speaker == null) return 0;
    final affected = _items.where((item) => _takesPart(item, speaker)).length;
    _speakerLibrary.remove(speaker);
    _speakerDescriptions.remove(name);
    _replaceSpeaker(speaker, null);
    return affected;
  }

  /// Library speaker called [name], ignoring case.
  Speaker? _speakerNamed(String name) =>
      _speakerLibrary.where((s) => s.name.toLowerCase() == name.toLowerCase()).firstOrNull;

  /// Whether [speaker] is heard in the transcription: confirmed for one of its
  /// diarizer slots or put on some of its segments by hand.
  bool _takesPart(Transcription item, Speaker speaker) {
    if (item.status != TranscriptionStatus.done) return false;
    final bySlot = _slotsOf(item.id).entries.any((e) => e.key <= item.speakerCount && e.value == speaker);
    final byHand = (_segmentSpeakers[item.id] ?? const <int, Speaker>{}).values.contains(speaker);
    return bySlot || byHand;
  }

  /// Swaps [old] for [replacement] in every transcription (and in pending
  /// suggestions), or takes them off when [replacement] is null.
  void _replaceSpeaker(Speaker old, Speaker? replacement) {
    // Slots of transcriptions nobody opened yet are created on demand: do it now,
    // or they would come back with the old speaker.
    for (final item in _items) {
      _slotsOf(item.id);
    }
    void apply(Map<int, Speaker> map) {
      for (final key in map.keys.toList()) {
        if (map[key] != old) continue;
        if (replacement == null) {
          map.remove(key);
        } else {
          map[key] = replacement;
        }
      }
    }

    _slotSpeakers.values.forEach(apply);
    _segmentSpeakers.values.forEach(apply);
    if (replacement != null) {
      for (final id in _suggestions.keys.toList()) {
        final s = _suggestions[id]!;
        _suggestions[id] = s.copyWith(speakers: {...s.speakers}..updateAll((_, v) => v == old ? replacement : v));
      }
    }
  }

  @override
  Future<Speaker> assignSpeaker(String id, Set<int> segmentIndexes, String name, {bool allBySpeaker = false}) async {
    final wanted = name.trim();
    // The library matches names case-insensitively; a new one keeps the case it was typed in.
    var speaker = _speakerLibrary.where((s) => s.name.toLowerCase() == wanted.toLowerCase()).firstOrNull;
    if (speaker == null) {
      speaker = Speaker(wanted, libraryPalette[_speakerLibrary.length % libraryPalette.length]);
      _speakerLibrary.add(speaker);
    }
    final assigned = _segmentSpeakers.putIfAbsent(id, () => {});
    final slots = _slotsOf(id);
    final marked = {...segmentIndexes}; // the segments that end up with a mark of their own

    if (allBySpeaker) {
      // A voice that is entirely among the segments is settled once and for all; its marks are dropped.
      final bySlot = <int, Set<int>>{};
      for (final index in segmentIndexes) {
        final slot = _transcript[index].speakerSlot;
        if (slot != null) bySlot.putIfAbsent(slot, () => {}).add(index);
      }
      for (final entry in bySlot.entries) {
        final all = [for (var i = 0; i < _transcript.length; i++) if (_transcript[i].speakerSlot == entry.key) i];
        if (entry.value.length == all.length) {
          slots[entry.key] = speaker;
          all.forEach(assigned.remove);
          marked.removeAll(entry.value);
        }
      }
    }

    for (final index in marked) {
      // A mark that only repeats what the voice already says is not kept.
      final slot = _transcript[index].speakerSlot;
      if (slot != null && slots[slot] == speaker) {
        assigned.remove(index);
      } else {
        assigned[index] = speaker;
      }
    }

    // A voice whose segments all end up with one speaker is that speaker's voice: record it, drop the marks.
    final bySlot = <int, List<int>>{};
    for (var i = 0; i < _transcript.length; i++) {
      final slot = _transcript[i].speakerSlot;
      if (slot != null) bySlot.putIfAbsent(slot, () => []).add(i);
    }
    for (final entry in bySlot.entries) {
      final who = {for (final i in entry.value) assigned[i] ?? slots[entry.key]};
      if (who.length == 1 && who.single != null) {
        slots[entry.key] = who.single!;
        entry.value.forEach(assigned.remove);
      }
    }
    return speaker;
  }

  @override
  Future<Tag> addTag(String id, String label) async {
    final wanted = label.trim().toLowerCase();
    final tag = _libraryTag(wanted) ?? _createTag(Tag(wanted, libraryPalette[_library.length % libraryPalette.length]));
    _link(id, tag);
    return tag;
  }

  Tag? _libraryTag(String label) => _library.where((t) => t.label == label).firstOrNull;

  Tag _createTag(Tag tag) {
    _library.add(tag);
    return tag;
  }

  /// Puts [tag] on a transcription unless it is already there.
  void _link(String id, Tag tag) {
    final index = _items.indexWhere((i) => i.id == id);
    if (index >= 0 && !_items[index].tags.contains(tag)) {
      _items[index] = _items[index].copyWith(tags: [..._items[index].tags, tag]);
    }
  }

  Speaker _librarySpeaker(Speaker suggested) {
    final existing = _speakerLibrary.where((s) => s.name == suggested.name).firstOrNull;
    if (existing != null) return existing;
    _speakerLibrary.add(suggested);
    return suggested;
  }

  /// Stores what is left of the suggestions, dropping the record when nothing is.
  void _keep(String id, Suggestions left) {
    if (left.isEmpty) {
      _suggestions.remove(id);
    } else {
      _suggestions[id] = left;
    }
  }

  void _acceptTag(String id, Tag suggested) => _link(id, _libraryTag(suggested.label) ?? _createTag(suggested));

  void _acceptSpeaker(String id, int slot, Speaker suggested) => _slotsOf(id)[slot] = _librarySpeaker(suggested);

  @override
  Future<void> removeTag(String id, String label) async {
    final index = _items.indexWhere((i) => i.id == id);
    if (index < 0) return;
    _items[index] = _items[index].copyWith(tags: [
      for (final t in _items[index].tags)
        if (t.label != label) t,
    ]);
  }

  // "Regenerate summary": the call starts it; a test ends it with [finishRegeneration] (the real one takes a
  // while on the gateway).
  final Set<String> _regenerating = {};
  final Map<String, String> _regenerationErrors = {};
  final Map<String, int> _regenerationsUsed = {};
  final Map<String, DateTime> _regeneratedAt = {};

  /// How many times a transcription may be regenerated, as the user's plan says (null: no limit).
  final int? regenerationLimit;
  final Map<String, String> _summaries = {};

  /// The ids [regenerateSummary] was called with.
  final List<String> regenerateCalls = [];

  /// Thrown by the next [regenerateSummary], once (the gateway refusing: already used, no service...).
  Object? regenerateError;

  @override
  Future<void> regenerateSummary(String id) async {
    await _wait();
    regenerateCalls.add(id);
    final error = regenerateError;
    if (error != null) {
      regenerateError = null;
      throw error;
    }
    _regenerationErrors.remove(id); // a new attempt has no old complaint
    _regenerating.add(id);
  }

  /// The gateway could not do the regeneration: it is not running any more, and says why ([message]) until the next attempt.
  void failRegeneration(String id, String message) {
    _regenerating.remove(id);
    _regenerationErrors[id] = message;
  }

  /// The gateway is done with a regeneration: the new [summary] is in, and the button is used up.
  void finishRegeneration(String id, String summary) {
    _regenerating.remove(id);
    _regenerationsUsed[id] = (_regenerationsUsed[id] ?? 0) + 1;
    _regeneratedAt[id] = DateTime(_year, 6, 24, 10, 0);
    _summaries[id] = summary;
  }

  @override
  Future<void> acceptAllSuggestions(String id) async {
    await _wait();
    final s = _suggestions[id];
    if (s == null) return;
    final title = s.title;
    if (title != null) await rename(id, title);
    for (final tag in s.tags) {
      _acceptTag(id, tag);
    }
    for (final entry in s.speakers.entries) {
      _acceptSpeaker(id, entry.key, entry.value);
    }
    _suggestions.remove(id);
  }

  @override
  Future<void> dismissAllSuggestions(String id) async {
    await _wait();
    _suggestions.remove(id);
  }

  @override
  Future<void> acceptTagSuggestion(String id, String label) async {
    await _wait();
    final s = _suggestions[id];
    final tag = s?.tags.where((t) => t.label == label).firstOrNull;
    if (s == null || tag == null) return;
    _acceptTag(id, tag);
    _keep(id, s.copyWith(tags: [for (final t in s.tags) if (t.label != label) t]));
  }

  @override
  Future<void> rejectTagSuggestion(String id, String label) async {
    await _wait();
    final s = _suggestions[id];
    if (s == null) return;
    _keep(id, s.copyWith(tags: [for (final t in s.tags) if (t.label != label) t]));
  }

  @override
  Future<void> acceptSpeakerSuggestion(String id, int slot) async {
    await _wait();
    final s = _suggestions[id];
    final speaker = s?.speakers[slot];
    if (s == null || speaker == null) return;
    _acceptSpeaker(id, slot, speaker);
    _keep(id, s.copyWith(speakers: {...s.speakers}..remove(slot)));
  }

  @override
  Future<void> rejectSpeakerSuggestion(String id, int slot) async {
    await _wait();
    final s = _suggestions[id];
    if (s == null) return;
    _keep(id, s.copyWith(speakers: {...s.speakers}..remove(slot)));
  }
}
