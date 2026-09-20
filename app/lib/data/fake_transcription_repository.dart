import 'dart:ui' show Color;

import '../models/speaker.dart';
import '../models/tag.dart';
import '../models/transcription.dart';
import '../models/transcription_detail.dart';
import 'transcription_repository.dart';

// Mock data copied from `designs/1.9/cards-teal.html`.
const _meeting = Tag('meeting', Color(0xFF2563EB));
const _quarterly = Tag('quarterly', Color(0xFF059669));
const _interview = Tag('interview', Color(0xFF7C3AED));
const _hiring = Tag('hiring', Color(0xFFD97706));
const _ideas = Tag('ideas', Color(0xFF0891B2));
const _standup = Tag('standup', Color(0xFF6366F1));

const _tags = <Tag>[_meeting, _quarterly, _interview, _hiring, _ideas, _standup];

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

/// Slots 1-3 mapped to people, for every item except the one that carries AI
/// suggestions in the design (id 6); its slots stay "Speaker N" until the
/// suggestions are accepted (iteration 7).
const _confirmedSpeakers = <int, Speaker>{1: _alexei, 2: _maria, 3: _james};
const _itemWithSuggestions = 6;

List<Transcription> _seedItems() => [
      Transcription(
        id: 0,
        title: 'Quarterly Review Meeting',
        duration: const Duration(minutes: 14),
        uploadedAt: DateTime(2025, 6, 22, 14, 41),
        status: TranscriptionStatus.done,
        tags: const [_meeting, _quarterly],
        speakerCount: 3,
      ),
      Transcription(
        id: 1,
        title: 'Economics Lecture — Part 2',
        duration: const Duration(minutes: 72),
        uploadedAt: DateTime(2025, 6, 22, 11, 5),
        status: TranscriptionStatus.processing,
        processingPct: 41,
      ),
      Transcription(
        id: 2,
        title: 'Interview: Anna K.',
        duration: const Duration(minutes: 47),
        uploadedAt: DateTime(2025, 6, 21, 16, 20),
        status: TranscriptionStatus.done,
        tags: const [_interview, _hiring],
        speakerCount: 2,
      ),
      Transcription(
        id: 3,
        title: 'Podcast Episode #47',
        duration: const Duration(minutes: 38),
        uploadedAt: DateTime(2025, 6, 22, 9, 30),
        status: TranscriptionStatus.pending,
      ),
      Transcription(
        id: 4,
        title: 'Product Ideas — Voice Memo',
        duration: const Duration(minutes: 12),
        uploadedAt: DateTime(2025, 6, 20, 18, 2),
        status: TranscriptionStatus.done,
        tags: const [_ideas],
      ),
      Transcription(
        id: 5,
        title: 'Daily Standup 19 Jun',
        duration: const Duration(minutes: 8),
        uploadedAt: DateTime(2025, 6, 19, 10, 0),
        status: TranscriptionStatus.done,
        tags: const [_meeting, _standup],
        speakerCount: 3,
      ),
      Transcription(
        id: 6,
        title: 'product_sync_2025_06_23.mp4',
        duration: const Duration(minutes: 34),
        uploadedAt: DateTime(2025, 6, 23, 13, 15),
        status: TranscriptionStatus.done,
        speakerCount: 3,
      ),
    ];

class FakeTranscriptionRepository implements TranscriptionRepository {
  /// In creation order: a higher id was created later. Mutable so that
  /// renaming shows up everywhere, like a real backend.
  final List<Transcription> _items = _seedItems();

  @override
  Future<List<Transcription>> list(TranscriptionQuery query) async {
    final q = query.search.trim().toLowerCase();
    final items = _items.where((i) {
      if (query.statuses.isNotEmpty && !query.statuses.contains(i.status)) return false;
      if (query.tags.isNotEmpty && !i.tags.any((t) => query.tags.contains(t.label))) return false;
      if (q.isNotEmpty && !i.title.toLowerCase().contains(q)) return false;
      return true;
    }).toList();

    switch (query.sort) {
      case TranscriptionSort.newest:
        items.sort((a, b) => b.id.compareTo(a.id));
      case TranscriptionSort.oldest:
        items.sort((a, b) => a.id.compareTo(b.id));
      case TranscriptionSort.longest:
        items.sort((a, b) => b.duration.compareTo(a.duration));
      case TranscriptionSort.name:
        items.sort((a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()));
    }
    return items;
  }

  @override
  Future<TranscriptionDetail?> get(int id) async {
    final index = _items.indexWhere((i) => i.id == id);
    if (index < 0 || _items[index].status != TranscriptionStatus.done) return null;
    return TranscriptionDetail(
      item: _items[index],
      segments: _transcript,
      speakers: id == _itemWithSuggestions ? const {} : _confirmedSpeakers,
    );
  }

  @override
  Future<void> rename(int id, String title) async {
    final index = _items.indexWhere((i) => i.id == id);
    if (index >= 0) _items[index] = _items[index].copyWith(title: title);
  }

  @override
  Future<List<Tag>> tags() async => _tags;
}
