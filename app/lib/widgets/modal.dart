import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';

import '../theme/app_palette.dart';

/// Shows [builder]'s content centered over a dimmed, blurred window
/// (`.backdrop`: `rgba(0,0,0,.35)` + `blur(4px)`); clicking the backdrop or
/// pressing Escape closes it. It covers the whole window, sidebar included.
Future<T?> showAppModal<T>(BuildContext context, {required WidgetBuilder builder}) {
  return showGeneralDialog<T>(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'Close',
    barrierColor: Colors.black.withValues(alpha: .35),
    transitionDuration: Duration.zero,
    pageBuilder: (context, _, _) => BackdropFilter(
      filter: ImageFilter.blur(sigmaX: 4, sigmaY: 4),
      // Material gives the content a text style and lets InkWells work.
      child: Center(child: Material(type: MaterialType.transparency, child: builder(context))),
    ),
  );
}

/// The white rounded card of a modal (`.edit-modal`, `.del-dialog`, `.modal`).
class ModalCard extends StatelessWidget {
  const ModalCard({
    super.key,
    required this.width,
    required this.radius,
    required this.shadow,
    required this.child,
  });

  final double width;
  final double radius;
  final BoxShadow shadow;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return ConstrainedBox(
      // `max-width: 95vw`
      constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * .95),
      child: Container(
        width: width,
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: p.surface,
          borderRadius: BorderRadius.circular(radius),
          boxShadow: [shadow],
        ),
        child: child,
      ),
    );
  }
}

enum ModalButtonKind { cancel, primary, danger }

/// Cancel / Save / Delete buttons of a modal (`.btn-cancel`, `.btn-save`, `.btn-del`).
class ModalButton extends StatefulWidget {
  const ModalButton({super.key, required this.label, required this.onPressed, this.kind = ModalButtonKind.primary});

  final String label;
  final VoidCallback onPressed;
  final ModalButtonKind kind;

  @override
  State<ModalButton> createState() => _ModalButtonState();
}

class _ModalButtonState extends State<ModalButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final (Color? fill, Color text, Color? border, EdgeInsets padding, FontWeight weight) = switch (widget.kind) {
      ModalButtonKind.cancel => (
          _hover ? p.background : null,
          p.text2,
          p.border,
          const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          FontWeight.w500,
        ),
      ModalButtonKind.primary => (
          _hover ? p.accentDark : p.accent,
          Colors.white,
          null,
          const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
          FontWeight.w600,
        ),
      ModalButtonKind.danger => (
          _hover ? const Color(0xFFDC2626) : p.danger,
          Colors.white,
          null,
          const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          FontWeight.w600,
        ),
    };
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onPressed,
        child: Container(
          padding: padding,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: fill,
            borderRadius: BorderRadius.circular(9),
            border: border == null ? null : Border.all(color: border),
          ),
          child: Text(widget.label, style: TextStyle(fontSize: 13, fontWeight: weight, color: text)),
        ),
      ),
    );
  }
}

/// The right-aligned row of buttons at the bottom of a modal (`.modal-foot`).
/// As in the design's flex row, the buttons stretch to the tallest one.
class ModalFooter extends StatelessWidget {
  const ModalFooter({super.key, required this.buttons});

  final List<Widget> buttons;

  @override
  Widget build(BuildContext context) {
    return IntrinsicHeight(
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < buttons.length; i++) ...[
            if (i > 0) const SizedBox(width: 8),
            buttons[i],
          ],
        ],
      ),
    );
  }
}

/// Title on the left, close button on the right, 20 px above the content
/// (`.modal-head`).
class ModalHeader extends StatelessWidget {
  const ModalHeader({super.key, required this.title, required this.onClose});

  final String title;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 20),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          _CloseButton(onTap: onClose),
        ],
      ),
    );
  }
}

class _CloseButton extends StatefulWidget {
  const _CloseButton({required this.onTap});

  final VoidCallback onTap;

  @override
  State<_CloseButton> createState() => _CloseButtonState();
}

class _CloseButtonState extends State<_CloseButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: Container(
          width: 30,
          height: 30,
          decoration: BoxDecoration(
            color: _hover ? p.background : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Icon(Icons.close_rounded, size: 20, color: p.text3),
        ),
      ),
    );
  }
}
