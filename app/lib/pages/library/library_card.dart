import 'package:flutter/material.dart';

import '../../models/transcription_link.dart';
import '../../theme/app_palette.dart';

/// A card of the Tags/Speakers grid (`.card`): white, rounded, lifts a little
/// on hover. [builder] gets whether the pointer is over the card, for the
/// edit/delete buttons that only show then.
class LibraryCard extends StatefulWidget {
  const LibraryCard({super.key, required this.builder, this.accentColor});

  final Widget Function(BuildContext context, bool hovered) builder;

  /// A 3 px bar across the top in this color (`.card-accent`).
  final Color? accentColor;

  @override
  State<LibraryCard> createState() => _LibraryCardState();
}

class _LibraryCardState extends State<LibraryCard> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: p.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: _hover ? p.accent.withValues(alpha: .3) : p.border),
          boxShadow: [
            if (_hover) BoxShadow(color: p.accent.withValues(alpha: .1), blurRadius: 16, offset: const Offset(0, 3)),
          ],
        ),
        // Passthrough: the card gets the (tight) height the grid row stretches it to,
        // so the description can take the free space.
        child: Stack(
          fit: StackFit.passthrough,
          children: [
            if (widget.accentColor != null)
              Positioned(top: 0, left: 0, right: 0, height: 3, child: ColoredBox(color: widget.accentColor!)),
            Padding(padding: const EdgeInsets.all(16), child: widget.builder(context, _hover)),
          ],
        ),
      ),
    );
  }
}

/// Edit and delete buttons at the top right of a card; visible on hover
/// (`.card-actions`).
class CardActions extends StatelessWidget {
  const CardActions({super.key, required this.visible, required this.onEdit, required this.onDelete, this.name = ''});

  final bool visible;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  /// What the buttons act on, for their tooltips ("Edit meeting").
  final String name;

  @override
  Widget build(BuildContext context) {
    return AnimatedOpacity(
      duration: const Duration(milliseconds: 150),
      opacity: visible ? 1 : 0,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _IconButton(icon: Icons.edit_rounded, tooltip: 'Edit $name', onTap: onEdit),
          const SizedBox(width: 1),
          _IconButton(icon: Icons.delete_rounded, tooltip: 'Delete $name', onTap: onDelete, danger: true),
        ],
      ),
    );
  }
}

class _IconButton extends StatefulWidget {
  const _IconButton({required this.icon, required this.tooltip, required this.onTap, this.danger = false});

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final bool danger;

  @override
  State<_IconButton> createState() => _IconButtonState();
}

class _IconButtonState extends State<_IconButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final (Color fill, Color color) = _hover
        ? (widget.danger ? (p.dangerBg, p.danger) : (p.background, p.text2))
        : (Colors.transparent, p.text3);
    return Tooltip(
      message: widget.tooltip,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: GestureDetector(
          onTap: widget.onTap,
          child: Container(
            width: 27,
            height: 27,
            decoration: BoxDecoration(color: fill, borderRadius: BorderRadius.circular(7)),
            child: Icon(widget.icon, size: 16, color: color),
          ),
        ),
      ),
    );
  }
}

/// The description text of a card, cut after three lines (`.card-desc`).
class CardDescription extends StatelessWidget {
  const CardDescription(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return text.isEmpty
        ? Text(
            'No description yet',
            style: TextStyle(fontSize: 13, height: 1.55, color: p.text3, fontStyle: FontStyle.italic),
          )
        : Text(text, maxLines: 3, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13, height: 1.55, color: p.text3));
  }
}

/// The rule, the "N files" line and the chips of linked transcriptions at the
/// bottom of a card (`.card-divider`, `.card-stat`, `.card-files`).
class CardUsage extends StatelessWidget {
  const CardUsage({
    super.key,
    required this.countLabel,
    required this.totalCount,
    required this.transcriptions,
    this.onOpen,
    this.onViewRest,
  });

  /// "3 files" / "2 transcriptions".
  final String countLabel;

  /// How many transcriptions actually carry this tag/speaker — the real total, which can be more than
  /// [transcriptions].length: the gateway only ever sends the latest couple of finished ones, so the "+N more"
  /// chip must count from this, never from the length of the list (that undercounts whenever the gateway's own
  /// cap is at or below what is shown here, and ignores those still being made).
  final int totalCount;

  /// The linked transcriptions the gateway sent (finished ones, already capped); the first two are shown as chips.
  final List<TranscriptionLink> transcriptions;

  /// Opens a transcription by its id when its chip is pressed; null leaves the chips as plain labels (used only
  /// where a test has no navigation to exercise).
  final ValueChanged<String>? onOpen;

  /// Opens the Transcriptions list filtered to just this tag/speaker; null hides the "+N more" chip's link
  /// (still shown as plain text) — used only where a test has no navigation to exercise.
  final VoidCallback? onViewRest;

  static const _shown = 2;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final rest = totalCount - transcriptions.length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 12, bottom: 10),
          child: SizedBox(height: 1, child: ColoredBox(color: p.border)),
        ),
        Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Row(
            children: [
              Icon(Icons.description_rounded, size: 13, color: p.text3),
              const SizedBox(width: 4),
              Text(countLabel, style: TextStyle(fontSize: 12, color: p.text3)),
            ],
          ),
        ),
        // Nothing is shown when it is used only by transcriptions that are not finished yet: there is no page to open.
        if (totalCount == 0)
          Text('Not used yet', style: TextStyle(fontSize: 11, color: p.text3, fontStyle: FontStyle.italic))
        else
          Wrap(
            spacing: 4,
            runSpacing: 4,
            children: [
              for (final link in transcriptions.take(_shown))
                _FileChip(link.title, onTap: onOpen == null ? null : () => onOpen!(link.id)),
              if (rest > 0) _MoreChip(rest, onTap: onViewRest),
            ],
          ),
      ],
    );
  }
}

class _FileChip extends StatefulWidget {
  const _FileChip(this.title, {this.onTap});

  final String title;

  /// Opens the transcription; with none the chip is only a label (no hand cursor).
  final VoidCallback? onTap;

  @override
  State<_FileChip> createState() => _FileChipState();
}

class _FileChipState extends State<_FileChip> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final chip = Tooltip(
      message: widget.title,
      child: MouseRegion(
        cursor: widget.onTap == null ? MouseCursor.defer : SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: Container(
          // Chrome rounds this line box up: 20 px in all.
          constraints: const BoxConstraints(maxWidth: 200, minHeight: 20),
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
          decoration: BoxDecoration(
            color: p.background,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: _hover ? p.accent : p.border),
          ),
          child: Text(
            widget.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 11, color: _hover ? p.accent : p.text2),
          ),
        ),
      ),
    );
    return widget.onTap == null ? chip : GestureDetector(onTap: widget.onTap, child: chip);
  }
}

/// "+N more →": the rest of the transcriptions that carry this tag/speaker, beyond the couple of sample
/// chips next to it. A distinct, deliberately small link — not the count line above and not the card itself,
/// which already opens editing — so it reads as "there is more, go look" rather than competing for the
/// card's one big click target.
class _MoreChip extends StatefulWidget {
  const _MoreChip(this.count, {required this.onTap});

  final int count;
  final VoidCallback? onTap;

  @override
  State<_MoreChip> createState() => _MoreChipState();
}

class _MoreChipState extends State<_MoreChip> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final active = widget.onTap != null;
    final color = active && _hover ? p.accent : p.text3;
    final text = Text(
      '+${widget.count} more${active ? ' →' : ''}',
      style: TextStyle(
        fontSize: 11,
        fontWeight: active ? FontWeight.w600 : FontWeight.w400,
        color: color,
        decoration: active && _hover ? TextDecoration.underline : TextDecoration.none,
      ),
    );
    final padded = Padding(padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2), child: text);
    if (!active) return padded;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(onTap: widget.onTap, child: padded),
    );
  }
}
