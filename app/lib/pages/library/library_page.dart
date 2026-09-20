import 'package:flutter/material.dart';

import '../../theme/app_palette.dart';
import '../../widgets/dashed_border.dart';

/// The frame shared by the Tags and Speakers pages: a header with the title,
/// a summary line and an "Add" button, a hint, and a responsive grid of cards
/// ending in a dashed "add" tile (`.page-hdr`, `.page-hint`, `.cards-grid`).
class LibraryPage extends StatelessWidget {
  const LibraryPage({
    super.key,
    required this.title,
    required this.subtitle,
    required this.addLabel,
    required this.onAdd,
    required this.hintIcon,
    required this.hint,
    required this.cards,
    this.minCardWidth = 240,
  });

  final String title;
  final String subtitle;

  /// Text of both add controls, e.g. "Add tag".
  final String addLabel;
  final VoidCallback onAdd;
  final IconData hintIcon;
  final String hint;
  final List<Widget> cards;

  /// The grid fits as many columns of at least this width as the window allows
  /// (`repeat(auto-fill, minmax(240px, 1fr))`).
  final double minCardWidth;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.fromLTRB(28, 20, 28, 16),
          decoration: BoxDecoration(border: Border(bottom: BorderSide(color: p.border))),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, letterSpacing: -.4)),
                  const SizedBox(height: 3),
                  Text(subtitle, style: TextStyle(fontSize: 13, color: p.text3)),
                ],
              ),
              _PrimaryButton(icon: Icons.add_rounded, label: addLabel, onTap: onAdd),
            ],
          ),
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _Hint(icon: hintIcon, text: hint),
                _CardGrid(
                  minCardWidth: minCardWidth,
                  children: [...cards, _AddCard(label: addLabel, onTap: onAdd)],
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _PrimaryButton extends StatefulWidget {
  const _PrimaryButton({required this.icon, required this.label, required this.onTap});

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  State<_PrimaryButton> createState() => _PrimaryButtonState();
}

class _PrimaryButtonState extends State<_PrimaryButton> {
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
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            color: _hover ? p.accentDark : p.accent,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(widget.icon, size: 16, color: Colors.white),
              const SizedBox(width: 6),
              Text(widget.label, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Colors.white)),
            ],
          ),
        ),
      ),
    );
  }
}

class _Hint extends StatelessWidget {
  const _Hint({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      margin: const EdgeInsets.only(bottom: 18),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: p.accentLight,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: p.border),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 1),
            child: Icon(icon, size: 18, color: p.accent),
          ),
          const SizedBox(width: 9),
          Expanded(child: Text(text, style: TextStyle(fontSize: 13, height: 1.5, color: p.text2))),
        ],
      ),
    );
  }
}

/// Equal-width columns, 12 px apart; the cards of a row are as tall as the
/// tallest one, like a CSS grid stretches them.
class _CardGrid extends StatelessWidget {
  const _CardGrid({required this.minCardWidth, required this.children});

  final double minCardWidth;
  final List<Widget> children;

  static const _gap = 12.0;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final columns = ((constraints.maxWidth + _gap) / (minCardWidth + _gap)).floor().clamp(1, 1 << 20);
        return Column(
          children: [
            for (var start = 0; start < children.length; start += columns) ...[
              if (start > 0) const SizedBox(height: _gap),
              IntrinsicHeight(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (var i = 0; i < columns; i++) ...[
                      if (i > 0) const SizedBox(width: _gap),
                      Expanded(child: start + i < children.length ? children[start + i] : const SizedBox.shrink()),
                    ],
                  ],
                ),
              ),
            ],
          ],
        );
      },
    );
  }
}

/// The dashed tile at the end of the grid (`.add-card`).
class _AddCard extends StatefulWidget {
  const _AddCard({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  State<_AddCard> createState() => _AddCardState();
}

class _AddCardState extends State<_AddCard> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = _hover ? p.accent : p.text3;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: DashedBorder(
          color: _hover ? p.accent : p.border,
          width: 2,
          radius: 14,
          dash: 6,
          gap: 4,
          child: Container(
            // `min-height: 164px` includes the 2 px border on each side.
            constraints: const BoxConstraints(minHeight: 160),
            decoration: BoxDecoration(
              color: _hover ? p.accentLight : Colors.transparent,
              borderRadius: BorderRadius.circular(12),
            ),
            alignment: Alignment.center,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.add_circle_rounded, size: 26, color: color),
                const SizedBox(height: 7),
                Text(widget.label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: color)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
