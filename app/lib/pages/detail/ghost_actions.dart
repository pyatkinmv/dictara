import 'package:flutter/material.dart';

import '../../theme/app_palette.dart';
import '../../widgets/button_spinner.dart';

/// ✓ / ✗ shown on a pending AI suggestion (`.ghost-ok`, `.ghost-x`). While its
/// action runs the icon is replaced by a spinner and clicks are ignored.
class GhostAction extends StatefulWidget {
  const GhostAction.accept({super.key, required this.busy, required this.onTap})
      : accept = true,
        tooltip = 'Accept';

  const GhostAction.reject({super.key, required this.busy, required this.onTap})
      : accept = false,
        tooltip = 'Dismiss';

  final bool accept;
  final bool busy;
  final VoidCallback onTap;
  final String tooltip;

  @override
  State<GhostAction> createState() => _GhostActionState();
}

class _GhostActionState extends State<GhostAction> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = widget.accept ? p.accent : (_hover ? p.text : p.text3);
    return Tooltip(
      message: widget.tooltip,
      child: MouseRegion(
        cursor: widget.busy ? SystemMouseCursors.basic : SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: GestureDetector(
          onTap: widget.busy ? null : widget.onTap,
          behavior: HitTestBehavior.opaque,
          child: SizedBox(
            width: 16,
            height: 16,
            child: Center(
              child: widget.busy
                  ? ButtonSpinner(color: color)
                  : Opacity(
                      opacity: widget.accept ? (_hover ? 1 : .8) : 1,
                      child: Icon(
                        widget.accept ? Icons.check_circle_rounded : Icons.close_rounded,
                        size: 16,
                        color: color,
                      ),
                    ),
            ),
          ),
        ),
      ),
    );
  }
}
