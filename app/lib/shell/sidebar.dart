import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../theme/app_palette.dart';
import 'nav_destinations.dart';
import 'profile_menu.dart';

const sidebarWidth = 220.0;

// Mock usage from the design; replaced by real plan data later.
const _mockPlanName = 'Free';
const _mockUsedMinutes = 38;
const _mockLimitMinutes = 60;

class Sidebar extends StatelessWidget {
  const Sidebar({super.key, required this.location});

  /// Current route path, used to highlight the active item.
  final String location;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    // Only a section's own page highlights its item: the design clears the
    // highlight while a transcription is open (`openDetail`).
    final active = navDestinations.where((d) => d.path == location).firstOrNull;
    final last = navDestinations.length - 1;

    return Container(
      width: sidebarWidth,
      padding: const EdgeInsets.fromLTRB(12, 20, 12, 20),
      decoration: BoxDecoration(
        color: p.sidebar,
        border: Border(right: BorderSide(color: p.border)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _Brand(),
          const SizedBox(height: 20),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < navDestinations.length; i++) ...[
                  // `.sb-nav` is a flex column with gap 2; the separator is one of its items.
                  if (i > 0) const SizedBox(height: 2),
                  if (i == last) ...const [_Separator(), SizedBox(height: 2)],
                  _NavItem(
                    destination: navDestinations[i],
                    active: navDestinations[i] == active,
                    onTap: () => context.go(navDestinations[i].path),
                  ),
                ],
              ],
            ),
          ),
          const _UsageBlock(),
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: ProfileMenu(),
          ),
        ],
      ),
    );
  }
}

class _Brand extends StatelessWidget {
  const _Brand();

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(color: p.accent, shape: BoxShape.circle),
            child: const Icon(Icons.mic_rounded, size: 18, color: Colors.white),
          ),
          const SizedBox(width: 8),
          Text(
            'Dictara',
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w700,
              letterSpacing: -.3,
              color: p.accent,
            ),
          ),
        ],
      ),
    );
  }
}

class _Separator extends StatelessWidget {
  const _Separator();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 8),
      child: SizedBox(height: 1, child: ColoredBox(color: context.palette.border)),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({required this.destination, required this.active, required this.onTap});

  final NavDestination destination;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = active ? p.accent : p.text2;
    return Material(
      color: active ? p.accent.withValues(alpha: .13) : Colors.transparent,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        hoverColor: active ? Colors.transparent : p.accent.withValues(alpha: .08),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
          child: Row(
            children: [
              Icon(destination.icon, size: 20, color: color),
              const SizedBox(width: 10),
              Text(
                destination.label,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: active ? FontWeight.w600 : FontWeight.w500,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _UsageBlock extends StatelessWidget {
  const _UsageBlock();

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final label = TextStyle(fontSize: 11, color: p.text3);
    return Container(
      margin: const EdgeInsets.only(top: 4),
      padding: const EdgeInsets.fromLTRB(10, 10, 10, 12),
      decoration: BoxDecoration(border: Border(top: BorderSide(color: p.border))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('Usage this month', style: label.copyWith(fontWeight: FontWeight.w500)),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
                decoration: BoxDecoration(color: p.accentLight, borderRadius: BorderRadius.circular(100)),
                child: Text(
                  _mockPlanName,
                  style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: p.accent),
                ),
              ),
            ],
          ),
          const SizedBox(height: 5),
          ClipRRect(
            borderRadius: BorderRadius.circular(2),
            child: SizedBox(
              height: 3,
              child: ColoredBox(
                color: p.border,
                child: FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: _mockUsedMinutes / _mockLimitMinutes,
                  child: ColoredBox(color: p.accent),
                ),
              ),
            ),
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Text('$_mockUsedMinutes / $_mockLimitMinutes min · ', style: label),
              const _UpgradeLink(),
            ],
          ),
        ],
      ),
    );
  }
}

class _UpgradeLink extends StatefulWidget {
  const _UpgradeLink();

  @override
  State<_UpgradeLink> createState() => _UpgradeLinkState();
}

class _UpgradeLinkState extends State<_UpgradeLink> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: Text(
        'Upgrade',
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w500,
          color: p.accent,
          decoration: _hover ? TextDecoration.underline : TextDecoration.none,
          decorationColor: p.accent,
        ),
      ),
    );
  }
}
