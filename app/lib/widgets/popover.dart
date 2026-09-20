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

  @override
  State<Popover> createState() => _PopoverState();
}

class _PopoverState extends State<Popover> {
  final _portal = OverlayPortalController();
  final _link = LayerLink();
  final _tapGroup = Object();

  void _toggle() => setState(_portal.toggle);

  void _close() {
    if (_portal.isShowing) setState(_portal.hide);
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final targetWidth = constraints.maxWidth;
        return CompositedTransformTarget(
          link: _link,
          child: OverlayPortal(
            controller: _portal,
            overlayChildBuilder: (context) => Align(
              alignment: Alignment.topLeft,
              child: CompositedTransformFollower(
                link: _link,
                targetAnchor: Alignment(widget.alignEnd ? 1 : -1, widget.above ? -1 : 1),
                followerAnchor: Alignment(widget.alignEnd ? 1 : -1, widget.above ? 1 : -1),
                offset: Offset(0, widget.above ? -widget.gap : widget.gap),
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
  const PopoverPanel({super.key, required this.child, this.padding = const EdgeInsets.all(8)});

  final Widget child;

  /// Space between the card edge and the content; zero lets content (a search
  /// field, a rule) reach the edge.
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: p.surface,
        borderRadius: BorderRadius.circular(appRadius),
        border: Border.all(color: p.border),
        boxShadow: [
          BoxShadow(color: p.accent.withValues(alpha: .12), blurRadius: 20, offset: const Offset(0, 4)),
        ],
      ),
      // Clip to the card's rounded corners, so content that reaches the edge
      // (a search field, a hover fill) does not poke out of them.
      // The border takes 1 px of space, as in CSS (`border` + `padding`).
      child: Padding(
        padding: const EdgeInsets.all(1),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(appRadius - 1),
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
