import 'dart:ui' show Color;

import '../models/tag.dart';
import '../models/transcription.dart';
import 'transcription_repository.dart';

// Mock data copied from `designs/1.9/cards-teal.html`.
const _meeting = Tag('meeting', Color(0xFF2563EB));
const _quarterly = Tag('quarterly', Color(0xFF059669));
const _interview = Tag('interview', Color(0xFF7C3AED));
const _hiring = Tag('hiring', Color(0xFFD97706));
const _ideas = Tag('ideas', Color(0xFF0891B2));
const _standup = Tag('standup', Color(0xFF6366F1));

final _tags = <Tag>[_meeting, _quarterly, _interview, _hiring, _ideas, _standup];

/// In creation order: a higher id was created later.
final _items = <Transcription>[
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
  Future<List<Tag>> tags() async => List.unmodifiable(_tags);
}
