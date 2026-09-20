import 'package:flutter/material.dart';

import '../theme/app_palette.dart';
import '../theme/app_theme.dart';

/// A panel that floats next to its target (`.dd-panel` in the design) and
/// closes when the user taps anywhere outside of the target and the panel.
class Popover extends StatefulWidget {
  const Popover({
    super.key,
    required this.targetBuilder,
    required this.panelBuilder,
    this.above = false,
    this.alignEnd = false,
    this.gap = 6,
    this.matchTargetWidth = false,
    this.minWidth = 0,
    this.estimatedSize,
    this.useRootOverlay = false,
  });

  /// Builds the element that opens the popover; call `toggle` to open/close.
  final Widget Function(BuildContext context, bool open, VoidCallback toggle) targetBuilder;

  /// Builds the panel (usually a [PopoverPanel]); call `close` to dismiss.
  final Widget Function(BuildContext context, VoidCallback close) panelBuilder;

  /// Open above the target instead of below.
  final bool above;

  /// Line the panel's right edge up with the target's instead of the left one,
  /// for targets near the right edge of the window.
  final bool alignEnd;

  /// Distance between the target and the panel.
  final double gap;

  /// Make the panel exactly as wide as the target; otherwise it is as wide as
  /// its content, but at least [minWidth].
  final bool matchTargetWidth;
  final double minWidth;

  /// The panel's rough size. When set, the popover looks at the space around
  /// the target as it opens: it flips above the target if there is no room
  /// below, and lines up with the target's right edge if it would run off the
  /// right side of the window.
  final Size? estimatedSize;

  /// Draw the panel in the app's root overlay, above everything (the design's
  /// `position: fixed`), instead of the page's own overlay, which the router
  /// nests inside the content area and which clips the panel to it.
  final bool useRootOverlay;

  @override
  State<Popover> createState() => _PopoverState();
}

class _PopoverState extends State<Popover> {
  final _portal = OverlayPortalController();
  final _link = LayerLink();
  final _tapGroup = Object();

  /// Placement chosen when the popover opened; null = as configured.
  ({bool above, bool alignEnd})? _placement;

  void _toggle() {
    if (!_portal.isShowing) _placement = _choosePlacement();
    setState(_portal.toggle);
  }

  void _close() {
    if (_portal.isShowing) setState(_portal.hide);
  }

  static const _windowMargin = 8.0;

  ({bool above, bool alignEnd})? _choosePlacement() {
    final size = widget.estimatedSize;
    final box = context.findRenderObject();
    if (size == null || box is! RenderBox || !box.hasSize) return null;

    final target = box.localToGlobal(Offset.zero) & box.size;
    final window = MediaQuery.sizeOf(context);
    final fitsBelow = target.bottom + widget.gap + size.height <= window.height - _windowMargin;
    final fitsAbove = target.top - widget.gap - size.height >= _windowMargin;
    final above = widget.above ? (fitsAbove || !fitsBelow) : (!fitsBelow && fitsAbove);
    final alignEnd = widget.alignEnd || target.left + size.width > window.width - _windowMargin;
    return (above: above, alignEnd: alignEnd);
  }

  @override
  Widget build(BuildContext context) {
    final above = _placement?.above ?? widget.above;
    final alignEnd = _placement?.alignEnd ?? widget.alignEnd;
    return LayoutBuilder(
      builder: (context, constraints) {
        final targetWidth = constraints.maxWidth;
        return CompositedTransformTarget(
          link: _link,
          child: OverlayPortal(
            controller: _portal,
            overlayLocation: widget.useRootOverlay
                ? OverlayChildLocation.rootOverlay
                : OverlayChildLocation.nearestOverlay,
            overlayChildBuilder: (context) => Align(
              alignment: Alignment.topLeft,
              child: CompositedTransformFollower(
                link: _link,
                targetAnchor: Alignment(alignEnd ? 1 : -1, above ? -1 : 1),
                followerAnchor: Alignment(alignEnd ? 1 : -1, above ? 1 : -1),
                offset: Offset(0, above ? -widget.gap : widget.gap),
                child: TapRegion(
                  groupId: _tapGroup,
                  child: widget.matchTargetWidth
                      ? SizedBox(width: targetWidth, child: widget.panelBuilder(context, _close))
                      : ConstrainedBox(
                          constraints: BoxConstraints(minWidth: widget.minWidth),
                          child: IntrinsicWidth(child: widget.panelBuilder(context, _close)),
                        ),
                ),
              ),
            ),
            child: TapRegion(
              groupId: _tapGroup,
              onTapOutside: (_) => _close(),
              child: widget.targetBuilder(context, _portal.isShowing, _toggle),
            ),
          ),
        );
      },
    );
  }
}

/// The white rounded card of a popover.
///
/// Opaque decoration outside, transparent [Material] inside: a popover lives in
/// the overlay, which has no Material ancestor for `InkWell`, and the hover
/// fills of its rows must paint above the white.
class PopoverPanel extends StatelessWidget {
  const PopoverPanel({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(8),
    this.radius = appRadius,
    this.shadow,
  });

  final Widget child;

  /// Space between the card edge and the content; zero lets content (a search
  /// field, a rule) reach the edge.
  final EdgeInsets padding;

  final double radius;

  /// Overrides the default accent-tinted shadow.
  final List<BoxShadow>? shadow;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: p.surface,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: p.border),
        boxShadow: shadow ??
            [BoxShadow(color: p.accent.withValues(alpha: .12), blurRadius: 20, offset: const Offset(0, 4))],
      ),
      // Clip to the card's rounded corners, so content that reaches the edge
      // (a search field, a hover fill) does not poke out of them.
      // The border takes 1 px of space, as in CSS (`border` + `padding`).
      child: Padding(
        padding: const EdgeInsets.all(1),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(radius - 1),
          child: Material(
            color: Colors.transparent,
            child: Padding(
              padding: padding,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [child],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Clickable row of a popover (`.dd-row`).
class PopoverRow extends StatelessWidget {
  const PopoverRow({super.key, required this.onTap, required this.child, this.danger = false});

  final VoidCallback onTap;
  final Widget child;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      hoverColor: danger ? p.dangerBg : p.accentLight,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
        child: DefaultTextStyle.merge(
          style: TextStyle(fontSize: 13, color: danger ? p.danger : p.text2),
          child: child,
        ),
      ),
    );
  }
}

/// Small uppercase caption above a group of rows (`.dd-section`).
class PopoverSection extends StatelessWidget {
  const PopoverSection(this.label, {super.key});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 4, 8, 2),
      child: Text(
        label.toUpperCase(),
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          letterSpacing: .8,
          color: context.palette.text3,
        ),
      ),
    );
  }
}

/// Horizontal rule between groups of rows (`.dd-sep`).
class PopoverSeparator extends StatelessWidget {
  const PopoverSeparator({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: SizedBox(height: 1, child: ColoredBox(color: context.palette.border)),
    );
  }
}
