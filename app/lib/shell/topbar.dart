import 'package:flutter/material.dart';

import '../theme/app_palette.dart';

const topbarHeight = 56.0;

class Topbar extends StatelessWidget {
  const Topbar({super.key, required this.title, required this.onUpload, this.onMenu});

  final String title;
  final VoidCallback onUpload;

  /// Phone layout only: opens the navigation drawer. Null on wide screens, where the sidebar is always there.
  final VoidCallback? onMenu;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final menu = onMenu;
    return Container(
      height: topbarHeight,
      padding: EdgeInsets.symmetric(horizontal: menu == null ? 24 : 8),
      decoration: BoxDecoration(
        color: p.surface,
        border: Border(bottom: BorderSide(color: p.border)),
      ),
      child: Row(
        children: [
          if (menu != null)
            IconButton(
              key: const Key('open-menu'),
              tooltip: 'Menu',
              onPressed: menu,
              icon: Icon(Icons.menu_rounded, color: p.text2),
            ),
          // A long transcription title is cut, so it can never push the Upload button off a narrow screen.
          Expanded(
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
            ),
          ),
          const SizedBox(width: 12),
          _UploadButton(onPressed: onUpload),
        ],
      ),
    );
  }
}

class _UploadButton extends StatefulWidget {
  const _UploadButton({required this.onPressed});

  final VoidCallback onPressed;

  @override
  State<_UploadButton> createState() => _UploadButtonState();
}

class _UploadButtonState extends State<_UploadButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onPressed,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [p.accent, p.accentDark],
            ),
            borderRadius: BorderRadius.circular(10),
            boxShadow: [
              BoxShadow(
                color: p.accent.withValues(alpha: _hover ? .35 : .25),
                blurRadius: _hover ? 14 : 8,
                offset: Offset(0, _hover ? 4 : 2),
              ),
            ],
          ),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.upload_rounded, size: 17, color: Colors.white),
              SizedBox(width: 6),
              Text(
                'Upload',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Colors.white),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
