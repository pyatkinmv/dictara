import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/auth_controller.dart';
import '../state/profile_controller.dart';
import '../theme/app_palette.dart';
import '../util/format.dart';
import '../widgets/popover.dart';

/// The signed-in name, or a plain fallback for the instant between login and the session actually landing.
String _nameOf(AuthController auth) => auth.displayName ?? 'Signed in';

/// Profile row at the bottom of the sidebar. Clicking it opens a menu that
/// floats above the row (`.profile-dd` in the design).
class ProfileMenu extends StatelessWidget {
  const ProfileMenu({super.key});

  @override
  Widget build(BuildContext context) {
    return Popover(
      above: true,
      matchTargetWidth: true,
      targetBuilder: (context, open, toggle) => _ProfileRow(open: open, onTap: toggle),
      panelBuilder: (context, close) => _MenuPanel(onSelected: close),
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
    final name = _nameOf(context.watch<AuthController>());
    final planName = context.watch<ProfileController>().profile?.planName;
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
                child: Text(
                  initialsOf(name),
                  style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w700),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(name, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600), overflow: TextOverflow.ellipsis),
                    if (planName != null) Text('$planName plan', style: TextStyle(fontSize: 11, color: p.text3)),
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
    final name = _nameOf(context.watch<AuthController>());
    final planName = context.watch<ProfileController>().profile?.planName;
    return PopoverPanel(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
            // Telegram gives no email; there is nothing true to put under the name, so nothing is.
            child: Text(name, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
          ),
          const PopoverSeparator(),
          _MenuRow(
            icon: Icons.star_rounded,
            label: 'Manage plan',
            trailing: planName == null
                ? null
                : Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
                    decoration: BoxDecoration(color: p.accentLight, borderRadius: BorderRadius.circular(100)),
                    child: Text(
                      planName,
                      style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: p.accent),
                    ),
                  ),
            onTap: onSelected,
          ),
          _MenuRow(icon: Icons.feedback_rounded, label: 'Give feedback', onTap: onSelected),
          _MenuRow(icon: Icons.help_outline_rounded, label: 'Support', onTap: onSelected),
          const PopoverSeparator(),
          _MenuRow(
            icon: Icons.logout_rounded,
            label: 'Sign out',
            danger: true,
            onTap: () {
              context.read<AuthController>().logout();
              onSelected();
            },
          ),
        ],
      ),
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
    return PopoverRow(
      onTap: onTap,
      danger: danger,
      child: Row(
        children: [
          Icon(icon, size: 16, color: danger ? p.danger : p.text2),
          const SizedBox(width: 8),
          Expanded(child: Text(label)),
          ?trailing,
        ],
      ),
    );
  }
}
