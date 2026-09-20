import 'dart:ui' show PathMetric;

import 'package:flutter/material.dart';

/// Draws a dashed rounded border around [child] (CSS `border: 1px dashed`);
/// Flutter has no built-in dashed border.
class DashedBorder extends StatelessWidget {
  const DashedBorder({
    super.key,
    required this.child,
    required this.color,
    this.width = 1,
    this.radius = 100,
    this.dash = 3,
    this.gap = 3,
  });

  final Widget child;
  final Color color;
  final double width;

  /// Corner radius; a large value gives a pill.
  final double radius;
  final double dash;
  final double gap;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      foregroundPainter: _DashedBorderPainter(color: color, width: width, radius: radius, dash: dash, gap: gap),
      // The border takes space, as a CSS border does.
      child: Padding(padding: EdgeInsets.all(width), child: child),
    );
  }
}

class _DashedBorderPainter extends CustomPainter {
  const _DashedBorderPainter({
    required this.color,
    required this.width,
    required this.radius,
    required this.dash,
    required this.gap,
  });

  final Color color;
  final double width;
  final double radius;
  final double dash;
  final double gap;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = (Offset.zero & size).deflate(width / 2);
    final r = radius.clamp(0.0, rect.shortestSide / 2);
    final path = Path()..addRRect(RRect.fromRectAndRadius(rect, Radius.circular(r)));
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width;

    for (final PathMetric metric in path.computeMetrics()) {
      var distance = 0.0;
      while (distance < metric.length) {
        final end = (distance + dash).clamp(0.0, metric.length);
        canvas.drawPath(metric.extractPath(distance, end), paint);
        distance += dash + gap;
      }
    }
  }

  @override
  bool shouldRepaint(_DashedBorderPainter old) =>
      old.color != color || old.width != width || old.radius != radius || old.dash != dash || old.gap != gap;
}
