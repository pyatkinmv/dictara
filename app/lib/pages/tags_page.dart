import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../models/tag_entry.dart';
import '../state/tag_library_controller.dart';
import '../state/transcription_list_controller.dart';
import '../theme/app_palette.dart';
import 'library/delete_dialog.dart';
import 'library/edit_item_dialog.dart';
import 'library/library_card.dart';
import 'library/library_page.dart';

/// The tag library as a grid of cards, with create, edit and delete.
class TagsPage extends StatefulWidget {
  const TagsPage({super.key});

  @override
  State<TagsPage> createState() => _TagsPageState();
}

class _TagsPageState extends State<TagsPage> {
  @override
  void initState() {
    super.initState();
    // Usage counts change as tags are put on transcriptions, so refresh on entry.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<TagLibraryController>().load();
    });
  }

  Future<void> _openForm(TagEntry? entry) async {
    final controller = context.read<TagLibraryController>();
    final form = await showEditItemDialog(
      context,
      title: entry == null ? 'New tag' : 'Edit tag',
      nameLabel: 'Tag name',
      namePlaceholder: 'e.g. meeting',
      descriptionPlaceholder: 'Describe when this applies…',
      hint: 'Descriptions help Dictara auto-tag future transcriptions.',
      initialName: entry?.tag.label ?? '',
      initialDescription: entry?.description ?? '',
      validate: (name) => controller.labelTaken(name, except: entry?.tag.label)
          ? 'A tag with this name already exists.'
          : null,
    );
    if (form == null) return;
    if (entry == null) {
      await controller.create(form.name, form.description);
    } else {
      await controller.update(entry.tag.label, newLabel: form.name, description: form.description);
    }
  }

  Future<void> _delete(TagEntry entry) async {
    final controller = context.read<TagLibraryController>();
    final confirmed = await showDeleteDialog(
      context,
      name: entry.tag.label,
      kind: 'tag',
      transcriptions: entry.count,
    );
    if (confirmed) await controller.delete(entry.tag.label);
  }

  void _viewInTranscriptions(TagEntry entry) {
    context.read<TranscriptionListController>().filterByTag(entry.tag.label);
    context.go('/transcriptions');
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<TagLibraryController>();
    // Nothing to draw before the first fetch: avoids flashing an empty grid.
    if (!controller.loaded) return const SizedBox.shrink();

    final entries = controller.entries;
    final used = controller.taggedTranscriptions;
    return LibraryPage(
      title: 'Tags',
      subtitle: '${entries.length} tag${entries.length == 1 ? '' : 's'} · '
          'used in $used file${used == 1 ? '' : 's'}',
      addLabel: 'Add tag',
      onAdd: () => _openForm(null),
      hintIcon: Icons.auto_awesome_rounded,
      hint: 'Tag descriptions help Dictara automatically suggest and apply tags to new transcriptions.',
      cards: [
        for (final entry in entries)
          LibraryCard(
            accentColor: entry.tag.color,
            builder: (context, hovered) => _TagCardContent(
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

class _TagCardContent extends StatelessWidget {
  const _TagCardContent({
    required this.entry,
    required this.hovered,
    required this.onEdit,
    required this.onDelete,
    required this.onViewRest,
  });

  final TagEntry entry;
  final bool hovered;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onViewRest;

  @override
  Widget build(BuildContext context) {
    final tag = entry.tag;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // `.card-top`: 6 px above, 9 px below.
        const SizedBox(height: 6),
        Row(
          children: [
            Container(width: 11, height: 11, decoration: BoxDecoration(color: tag.color, shape: BoxShape.circle)),
            const SizedBox(width: 9),
            Expanded(
              child: Text(
                tag.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: context.palette.text),
              ),
            ),
            const SizedBox(width: 9), // `.card-top` is a flex row with a 9 px gap
            CardActions(visible: hovered, name: tag.label, onEdit: onEdit, onDelete: onDelete),
          ],
        ),
        const SizedBox(height: 9),
        Expanded(child: Align(alignment: Alignment.topLeft, child: CardDescription(entry.description))),
        CardUsage(
          countLabel: '${entry.count} file${entry.count == 1 ? '' : 's'}',
          totalCount: entry.count,
          titles: entry.transcriptions,
          onViewRest: onViewRest,
        ),
      ],
    );
  }
}
