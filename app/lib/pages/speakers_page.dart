import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../models/speaker_entry.dart';
import '../state/speaker_library_controller.dart';
import '../state/transcription_list_controller.dart';
import '../theme/app_palette.dart';
import '../util/format.dart';
import 'library/delete_dialog.dart';
import 'library/edit_item_dialog.dart';
import 'library/library_card.dart';
import 'library/library_page.dart';

/// The speaker library as a grid of cards, with create, edit and delete.
class SpeakersPage extends StatefulWidget {
  const SpeakersPage({super.key});

  @override
  State<SpeakersPage> createState() => _SpeakersPageState();
}

class _SpeakersPageState extends State<SpeakersPage> {
  @override
  void initState() {
    super.initState();
    // Usage changes as speakers are put on transcriptions, so refresh on entry.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<SpeakerLibraryController>().load();
    });
  }

  Future<void> _openForm(SpeakerEntry? entry) async {
    final controller = context.read<SpeakerLibraryController>();
    final form = await showEditItemDialog(
      context,
      title: entry == null ? 'New speaker' : 'Edit speaker',
      nameLabel: 'Full name',
      namePlaceholder: 'e.g. Alexei Sorokin',
      descriptionPlaceholder: 'Role, topics, how they talk…',
      hint:
          "Describe this speaker's role and topics. Used for auto-identification.",
      initialName: entry?.speaker.name ?? '',
      initialDescription: entry?.description ?? '',
      validate: (name) =>
          controller.nameTaken(name, except: entry?.speaker.name)
          ? 'A speaker with this name already exists.'
          : null,
    );
    if (form == null) return;
    if (entry == null) {
      await controller.create(form.name, form.description);
    } else {
      await controller.update(
        entry.speaker.name,
        newName: form.name,
        description: form.description,
      );
    }
  }

  Future<void> _delete(SpeakerEntry entry) async {
    final controller = context.read<SpeakerLibraryController>();
    final confirmed = await showDeleteDialog(
      context,
      name: entry.speaker.name,
      kind: 'speaker',
      transcriptions: entry.count,
    );
    if (confirmed) await controller.delete(entry.speaker.name);
  }

  void _viewInTranscriptions(SpeakerEntry entry) {
    context.read<TranscriptionListController>().filterBySpeaker(entry.speaker.name);
    context.go('/transcriptions');
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<SpeakerLibraryController>();
    // Nothing to draw before the first fetch: avoids flashing an empty grid.
    if (!controller.loaded) return const SizedBox.shrink();

    final entries = controller.entries;
    return LibraryPage(
      title: 'Speakers',
      subtitle: '${entries.length} speaker${entries.length == 1 ? '' : 's'}',
      addLabel: 'Add speaker',
      onAdd: () => _openForm(null),
      hintIcon: Icons.record_voice_over_rounded,
      hint:
          'Named speakers help identify voices in future transcriptions automatically.',
      minCardWidth: 280,
      cards: [
        for (final entry in entries)
          LibraryCard(
            builder: (context, hovered) => _SpeakerCardContent(
              entry: entry,
              hovered: hovered,
              onEdit: () => _openForm(entry),
              onDelete: () => _delete(entry),
              onViewRest: () => _viewInTranscriptions(entry),
            ),
          ),
      ],
    );
  }
}

class _SpeakerCardContent extends StatelessWidget {
  const _SpeakerCardContent({
    required this.entry,
    required this.hovered,
    required this.onEdit,
    required this.onDelete,
    required this.onViewRest,
  });

  final SpeakerEntry entry;
  final bool hovered;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onViewRest;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final speaker = entry.speaker;
    final count = entry.count;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // `.spk-top`: avatar, name with two stats, actions; 9 px below.
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: speaker.color,
                shape: BoxShape.circle,
              ),
              child: Text(
                speaker.initials,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: Colors.white,
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    speaker.name,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                      height: 1.2,
                      color: p.text,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Wrap(
                    spacing: 10,
                    children: [
                      _Stat(
                        icon: Icons.description_rounded,
                        text: '$count file${count == 1 ? '' : 's'}',
                      ),
                      _Stat(
                        icon: Icons.schedule_rounded,
                        text: formatDuration(entry.totalDuration),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(width: 10),
            CardActions(
              visible: hovered,
              name: speaker.name,
              onEdit: onEdit,
              onDelete: onDelete,
            ),
          ],
        ),
        const SizedBox(height: 9),
        Expanded(
          child: Align(
            alignment: Alignment.topLeft,
            child: CardDescription(entry.description),
          ),
        ),
        CardUsage(
          countLabel: '$count transcription${count == 1 ? '' : 's'}',
          totalCount: count,
          titles: entry.transcriptions,
          onViewRest: onViewRest,
        ),
      ],
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 12, color: p.text3),
        const SizedBox(width: 2),
        Text(text, style: TextStyle(fontSize: 11, color: p.text3)),
      ],
    );
  }
}
