import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../state/auth_controller.dart';
import '../../state/profile_controller.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';

/// The Profile tab: who you are, how you sign in, your plan and what is used of it, and Sign out.
class ProfileTab extends StatelessWidget {
  const ProfileTab({super.key});

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final name = context.watch<AuthController>().displayName ?? 'Signed in';
    final profile = context.watch<ProfileController>().profile;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _Card(
          child: Row(
            children: [
              Container(
                width: 48,
                height: 48,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: p.accent, shape: BoxShape.circle),
                child: Text(
                  initialsOf(name),
                  style: const TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.w700),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(name, key: const Key('profile-name'), style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 2),
                    Text('Signed in with Telegram', style: TextStyle(fontSize: 12.5, color: p.text3)),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Plan', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              const SizedBox(height: 10),
              if (profile == null)
                Text('Loading…', style: TextStyle(fontSize: 13, color: p.text3))
              else ...[
                Row(
                  children: [
                    Text('Current plan', style: TextStyle(fontSize: 13, color: p.text2)),
                    const Spacer(),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 2),
                      decoration: BoxDecoration(color: p.accentLight, borderRadius: BorderRadius.circular(100)),
                      child: Text(
                        profile.planName,
                        key: const Key('profile-plan'),
                        style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: p.accent),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  profile.usage.max == null
                      ? '${profile.usage.used} ${profile.usage.unit} this month'
                      : '${profile.usage.used} of ${profile.usage.max} ${profile.usage.unit} this month',
                  key: const Key('profile-usage'),
                  style: TextStyle(fontSize: 13, color: p.text2),
                ),
                if (profile.usage.max != null) ...[
                  const SizedBox(height: 8),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: profile.usage.max == 0 ? 0 : (profile.usage.used / profile.usage.max!).clamp(0.0, 1.0),
                      minHeight: 7,
                      backgroundColor: p.border,
                      valueColor: AlwaysStoppedAnimation(p.accent),
                    ),
                  ),
                ],
              ],
            ],
          ),
        ),
        const SizedBox(height: 12),
        _Card(
          child: Row(
            children: [
              Expanded(child: Text('Sign out on this device', style: TextStyle(fontSize: 13, color: p.text2))),
              _SignOutButton(onTap: context.read<AuthController>().logout),
            ],
          ),
        ),
      ],
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: p.surface, borderRadius: BorderRadius.circular(14), border: Border.all(color: p.border)),
      child: child,
    );
  }
}

class _SignOutButton extends StatefulWidget {
  const _SignOutButton({required this.onTap});

  final VoidCallback onTap;

  @override
  State<_SignOutButton> createState() => _SignOutButtonState();
}

class _SignOutButtonState extends State<_SignOutButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        key: const Key('profile-sign-out'),
        onTap: widget.onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          decoration: BoxDecoration(
            color: _hover ? p.dangerBg : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
            border: Border.all(color: p.danger.withValues(alpha: .45)),
          ),
          child: Text('Sign out', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: p.danger)),
        ),
      ),
    );
  }
}
