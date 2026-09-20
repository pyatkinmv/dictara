import 'package:flutter/material.dart';

import '../theme/app_palette.dart';

/// Small checkbox drawn to match the design (native `input[type=checkbox]`
/// tinted with the accent). Purely visual: the enclosing row handles taps.
class CheckMark extends StatelessWidget {
  const CheckMark({super.key, required this.checked});

  final bool checked;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      width: 16,
      height: 16,
      decoration: BoxDecoration(
        color: checked ? p.accent : p.surface,
        borderRadius: BorderRadius.circular(3.5),
        border: Border.all(color: checked ? p.accent : p.text3, width: 1.2),
      ),
      child: checked ? const Icon(Icons.check_rounded, size: 13, color: Colors.white) : null,
    );
  }
}

/// Small radio dot, the single-choice counterpart of [CheckMark].
class RadioMark extends StatelessWidget {
  const RadioMark({super.key, required this.selected});

  final bool selected;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      width: 16,
      height: 16,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: p.surface,
        border: Border.all(color: selected ? p.accent : p.text3, width: selected ? 1.6 : 1.2),
      ),
      child: selected
          ? Container(width: 8, height: 8, decoration: BoxDecoration(color: p.accent, shape: BoxShape.circle))
          : null,
    );
  }
}
