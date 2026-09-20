import 'package:flutter/material.dart';

/// The small spinner that replaces a button's content while its action runs
/// (`.btn-spinner`: 11 px, 2 px ring in the current color at 55 % opacity).
class ButtonSpinner extends StatelessWidget {
  const ButtonSpinner({super.key, required this.color, this.size = 11});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CircularProgressIndicator(
        strokeWidth: 2,
        strokeCap: StrokeCap.butt,
        valueColor: AlwaysStoppedAnimation(color.withValues(alpha: .55)),
      ),
    );
  }
}
