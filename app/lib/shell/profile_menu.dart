import 'package:flutter/material.dart';

import '../theme/app_palette.dart';
import '../theme/app_theme.dart';

// Mock data from the design; replaced by real user/plan data later.
const _mockName = 'Maxim P.';
const _mockEmail = 'pyatkinmv@gmail.com';
const _mockPlan = 'Free';

/// Profile row at the bottom of the sidebar. Clicking it opens a menu that
/// floats above the row (`.profile-dd` in the design).
class ProfileMenu extends StatefulWidget {
  const ProfileMenu({super.key});

  @override
  State<ProfileMenu> createState() => _ProfileMenuState();
}

class _ProfileMenuState extends State<ProfileMenu> {
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
        final width = constraints.maxWidth;
        return CompositedTransformTarget(
          link: _link,
          child: OverlayPortal(
            controller: _portal,
            overlayChildBuilder: (context) => Align(
              alignment: Alignment.topLeft,
              child: CompositedTransformFollower(
                link: _link,
                targetAnchor: Alignment.topLeft,
                followerAnchor: Alignment.bottomLeft,
                offset: const Offset(0, -6),
                child: TapRegion(
                  groupId: _tapGroup,
                  child: SizedBox(
                    width: width,
                    child: _MenuPanel(onSelected: _close),
                  ),
                ),
              ),
            ),
            child: TapRegion(
              groupId: _tapGroup,
              onTapOutside: (_) => _close(),
              child: _ProfileRow(open: _portal.isShowing, onTap: _toggle),
            ),
          ),
        );
      },
    );
  }
}

class _ProfileRow extends StatelessWidget {
  const _ProfileRow({required this.open, required this.onTap});

  final bool open;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        hoverColor: p.accent.withValues(alpha: .08),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          child: Row(
            children: [
              Container(
                width: 30,
                height: 30,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: p.accent, shape: BoxShape.circle),
                child: const Text(
                  'M',
                  style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w700),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(_mockName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                    Text('$_mockPlan plan', style: TextStyle(fontSize: 11, color: p.text3)),
                  ],
                ),
              ),
              AnimatedRotation(
                turns: open ? .5 : 0,
                duration: const Duration(milliseconds: 200),
                child: Icon(Icons.expand_less_rounded, size: 16, color: p.text3),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MenuPanel extends StatelessWidget {
  const _MenuPanel({required this.onSelected});

  final VoidCallback onSelected;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    // Opaque decoration outside, transparent Material inside: the overlay has no
    // Material ancestor for InkWell, and hover fills must paint above the white.
    return DecoratedBox(
      decoration: BoxDecoration(
        color: p.surface,
        borderRadius: BorderRadius.circular(appRadius),
        border: Border.all(color: p.border),
        boxShadow: [
          BoxShadow(color: p.accent.withValues(alpha: .12), blurRadius: 20, offset: const Offset(0, 4)),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(_mockName, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 1),
                    Text(_mockEmail, style: TextStyle(fontSize: 11, color: p.text3)),
                  ],
                ),
              ),
              _Separator(color: p.border),
              _MenuRow(
                icon: Icons.star_rounded,
                label: 'Manage plan',
                trailing: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
                  decoration: BoxDecoration(color: p.accentLight, borderRadius: BorderRadius.circular(100)),
                  child: Text(
                    _mockPlan,
                    style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: p.accent),
                  ),
                ),
                onTap: onSelected,
              ),
              _MenuRow(icon: Icons.feedback_rounded, label: 'Give feedback', onTap: onSelected),
              _MenuRow(icon: Icons.help_outline_rounded, label: 'Support', onTap: onSelected),
              _Separator(color: p.border),
              _MenuRow(icon: Icons.logout_rounded, label: 'Sign out', danger: true, onTap: onSelected),
            ],
          ),
        ),
      ),
    );
  }
}

class _Separator extends StatelessWidget {
  const _Separator({required this.color});

  final Color color;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: SizedBox(height: 1, child: ColoredBox(color: color)),
    );
  }
}

class _MenuRow extends StatelessWidget {
  const _MenuRow({
    required this.icon,
    required this.label,
    required this.onTap,
    this.trailing,
    this.danger = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final Widget? trailing;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = danger ? p.danger : p.text2;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      hoverColor: danger ? p.dangerBg : p.accentLight,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
        child: Row(
          children: [
            Icon(icon, size: 16, color: color),
            const SizedBox(width: 8),
            Expanded(child: Text(label, style: TextStyle(fontSize: 13, color: color))),
            ?trailing,
          ],
        ),
      ),
    );
  }
}
