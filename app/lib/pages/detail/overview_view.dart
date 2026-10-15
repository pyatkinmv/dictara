import 'dart:async';

import 'package:flutter/material.dart';

import 'package:provider/provider.dart';

import '../../models/transcription_detail.dart';
import '../../state/transcription_detail_controller.dart';
import '../../widgets/button_spinner.dart';
import '../../shell/breakpoints.dart';
import '../../theme/app_palette.dart';
import '../../theme/app_theme.dart';
import '../../util/format.dart';
import '../../widgets/summary_markdown.dart';
import 'regenerate_dialog.dart';

/// The Overview tab: the AI summary on the left, file facts on the right
/// (`.ov-pane`).
///
/// The design keeps the details panel sticky while the summary scrolls; here
/// it scrolls with the page, which only shows once the summary outgrows the
/// window.
class OverviewView extends StatelessWidget {
  const OverviewView({super.key, required this.detail});

  final TranscriptionDetail detail;

  @override
  Widget build(BuildContext context) {
    final phone = isMobileWidth(context);
    return SingleChildScrollView(
      padding: EdgeInsets.symmetric(horizontal: phone ? 12 : 24, vertical: 20),
      // On a phone the file facts go under the summary instead of squeezing it into a sliver.
      child: phone
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _SummaryCard(detail: detail),
                const SizedBox(height: 14),
                _DetailsPanel(detail: detail),
              ],
            )
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: _SummaryCard(detail: detail)),
                const SizedBox(width: 18),
                SizedBox(width: 224, child: _DetailsPanel(detail: detail)),
              ],
            ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({required this.detail});

  final TranscriptionDetail detail;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    // Markdown, as the summarizer wrote it; null when there is none.
    final text = detail.summary;
    final control = _RegenerateControl(detail: detail);
    final hasControl = detail.regeneration != SummaryRegeneration.unavailable;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
      decoration: BoxDecoration(
        color: p.surface,
        borderRadius: BorderRadius.circular(appRadius),
        border: Border.all(color: p.border),
      ),
      child: SelectionArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (text == null && hasControl) _SectionHeader(label: 'Summary', trailing: control),
            if (text == null)
              Padding(
                padding: EdgeInsets.only(top: hasControl ? 8 : 0),
                child: Text('No summary available.', style: TextStyle(fontSize: 13.5, color: p.text3)),
              )
            else
              _Section(label: 'Summary', trailing: hasControl ? control : null, child: SummaryMarkdown(data: text)),
          ],
        ),
      ),
    );
  }
}

/// The label of a section, with something at its right end.
class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.label, this.trailing});

  final String label;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Row(
      children: [
        Expanded(
          child: Text(
            label.toUpperCase(),
            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w800, letterSpacing: .8, color: p.accent),
          ),
        ),
        ?trailing,
      ],
    );
  }
}

/// "Regenerate" while it can be done, a spinner while it runs, the date once it is used. Asks first: it
/// replaces the summary and cannot be repeated.
class _RegenerateControl extends StatelessWidget {
  const _RegenerateControl({required this.detail});

  final TranscriptionDetail detail;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final small = TextStyle(fontSize: 12, color: p.text3);
    switch (detail.regeneration) {
      case SummaryRegeneration.unavailable:
        return const SizedBox.shrink();
      case SummaryRegeneration.running:
        return Row(
          key: const Key('regenerating'),
          mainAxisSize: MainAxisSize.min,
          children: [
            ButtonSpinner(color: p.accent, size: 12),
            const SizedBox(width: 6),
            Text('Regenerating…', style: small),
          ],
        );
      case SummaryRegeneration.used:
        final at = detail.regeneratedAt;
        return Text(at == null ? 'Regenerated' : 'Regenerated ${formatDay(at)}', key: const Key('regenerated'), style: small);
      case SummaryRegeneration.available:
        return InkWell(
          key: const Key('regenerate-summary'),
          borderRadius: BorderRadius.circular(8),
          onTap: () async {
            final controller = context.read<TranscriptionDetailController>();
            if (await showRegenerateDialog(context)) await controller.regenerateSummary();
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.refresh_rounded, size: 15, color: p.text2),
                const SizedBox(width: 4),
                Text('Regenerate', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: p.text2)),
              ],
            ),
          ),
        );
    }
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.label, required this.child, this.trailing});

  final String label;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionHeader(label: label, trailing: trailing),
        const SizedBox(height: 8),
        child,
      ],
    );
  }
}

class _DetailsPanel extends StatelessWidget {
  const _DetailsPanel({required this.detail});

  final TranscriptionDetail detail;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final item = detail.item;
    final chips = <Widget>[
      _FilenameChip(filename: detail.originalFilename),
      _DetailChip(icon: Icons.schedule_rounded, value: item.duration != null ? formatTimestamp(item.duration!) : '—', label: 'Duration'),
      _DetailChip(
        icon: Icons.record_voice_over_rounded,
        // The same count the "Speakers:" header row shows (distinct speakers actually present), not the
        // number of diarizer voices — a manual reassignment can merge or split it just as it does the header.
        value: item.speakerCount > 0 ? '${detail.presentSpeakers.length}' : '—',
        label: 'Speakers',
      ),
      _DetailChip(icon: Icons.translate_rounded, value: detail.language ?? '—', label: 'Language'),
      _DetailChip(
        icon: Icons.text_fields_rounded,
        value: detail.wordCount == null ? '—' : '~${formatCount(detail.wordCount!)}',
        label: 'Words',
      ),
      _DetailChip(icon: Icons.storage_rounded, value: formatFileSize(detail.fileSizeBytes), label: 'File size'),
      _DetailChip(
        icon: Icons.calendar_today_rounded,
        value: formatDayTime(item.uploadedAt),
        label: 'Uploaded',
        valueSize: 12,
      ),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'DETAILS',
          style: TextStyle(fontSize: 10, fontWeight: FontWeight.w800, letterSpacing: .8, color: p.text3),
        ),
        const SizedBox(height: 10),
        // Two columns, 8 px apart; chips in a row are as tall as the tallest one.
        for (var i = 0; i < chips.length; i += 2) ...[
          if (i > 0) const SizedBox(height: 8),
          IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(child: chips[i]),
                const SizedBox(width: 8),
                Expanded(child: i + 1 < chips.length ? chips[i + 1] : const SizedBox.shrink()),
              ],
            ),
          ),
        ],
      ],
    );
  }
}

/// One fact of the details panel (`.det-chip`).
class _DetailChip extends StatelessWidget {
  const _DetailChip({required this.icon, required this.value, required this.label, this.valueSize = 14});

  final IconData icon;
  final String value;
  final String label;
  final double valueSize;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
      decoration: BoxDecoration(
        color: p.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: p.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: p.accent),
          const SizedBox(height: 6), // 4 px icon margin + 2 px column gap
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: valueSize, fontWeight: FontWeight.w700, height: 1.2, color: p.text),
          ),
          const SizedBox(height: 4), // 2 px label margin + 2 px column gap
          Text(
            label.toUpperCase(),
            style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, letterSpacing: .63, color: p.text3),
          ),
        ],
      ),
    );
  }
}

/// The "Original file" chip. The name is usually cut off, so hovering shows a
/// floating box with the full name next to the pointer. The box stays while
/// the pointer is over it and its text is selectable, so the name can be copied.
class _FilenameChip extends StatefulWidget {
  const _FilenameChip({required this.filename});

  final String filename;

  @override
  State<_FilenameChip> createState() => _FilenameChipState();
}

class _FilenameChipState extends State<_FilenameChip> {
  final _portal = OverlayPortalController();
  Offset _pointer = Offset.zero;
  Timer? _hideTimer;

  bool _overChip = false;
  bool _overBox = false;

  void _enterChip(Offset globalPosition) {
    _overChip = true;
    _hideTimer?.cancel();
    if (_portal.isShowing) return;
    _pointer = globalPosition;
    setState(_portal.show);
  }

  // Hidden only when the pointer is over neither the chip nor the box, and after
  // a short delay so it can cross the gap between them. Tracking both flags keeps
  // this independent of the order of the exit/enter events.
  void _leave() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(milliseconds: 120), () {
      if (mounted && !_overChip && !_overBox && _portal.isShowing) setState(_portal.hide);
    });
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    // The root overlay, not the page's: the router nests a Navigator (and an
    // Overlay) inside the shell, which would offset the pointer coordinates
    // and clip the box to the content area.
    return OverlayPortal(
      controller: _portal,
      overlayLocation: OverlayChildLocation.rootOverlay,
      overlayChildBuilder: (context) => CustomSingleChildLayout(
        delegate: _NextToPointer(_pointer),
        child: MouseRegion(
          onEnter: (_) => _overBox = true,
          onExit: (_) {
            _overBox = false;
            _leave();
          },
          child: TweenAnimationBuilder<double>(
            tween: Tween(begin: 0, end: 1),
            duration: const Duration(milliseconds: 150),
            builder: (context, opacity, child) => Opacity(opacity: opacity, child: child),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: p.surface,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: p.border),
                boxShadow: const [BoxShadow(color: Color(0x1A000000), blurRadius: 14, offset: Offset(0, 4))],
              ),
              // The overlay has no Material ancestor; SelectableText needs one for its text style.
              child: Material(
                color: Colors.transparent,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
                  child: SelectableText(
                    widget.filename,
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: p.text),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
      child: MouseRegion(
        onEnter: (event) => _enterChip(event.position),
        onExit: (_) {
          _overChip = false;
          _leave();
        },
        child: _DetailChip(
          icon: Icons.insert_drive_file_rounded, // closest to Material Symbols "draft"
          value: widget.filename,
          label: 'Original file',
          valueSize: 12,
        ),
      ),
    );
  }
}

/// Places a box 14 px right of and below the pointer, like the design, but
/// keeps it inside the window: the design's box runs off the right edge when
/// the chip is near it.
class _NextToPointer extends SingleChildLayoutDelegate {
  const _NextToPointer(this.pointer);

  final Offset pointer;

  static const _offset = 14.0;
  static const _margin = 8.0;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) => constraints.loosen();

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    final maxX = size.width - childSize.width - _margin;
    final x = (pointer.dx + _offset).clamp(_margin, maxX < _margin ? _margin : maxX);
    // Flip above the pointer when there is no room below.
    final below = pointer.dy + _offset;
    final y = below + childSize.height + _margin > size.height ? pointer.dy - _offset - childSize.height : below;
    return Offset(x.toDouble(), y);
  }

  @override
  bool shouldRelayout(_NextToPointer old) => old.pointer != pointer;
}
