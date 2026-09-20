import 'package:flutter/material.dart';

class NavDestination {
  const NavDestination(this.path, this.label, this.icon);

  final String path;
  final String label;
  final IconData icon;
}

/// Main navigation, in sidebar order. The design puts a separator before the
/// last item (Settings).
const navDestinations = <NavDestination>[
  NavDestination('/transcriptions', 'Transcriptions', Icons.description_rounded),
  NavDestination('/threads', 'Threads', Icons.forum_rounded),
  NavDestination('/tags', 'Tags', Icons.sell_rounded),
  NavDestination('/speakers', 'Speakers', Icons.record_voice_over_rounded),
  NavDestination('/settings', 'Settings', Icons.settings_rounded),
];

/// The destination whose section contains [location] (e.g. a detail page
/// `/transcriptions/42` belongs to `/transcriptions`).
NavDestination? destinationFor(String location) {
  for (final d in navDestinations) {
    if (location == d.path || location.startsWith('${d.path}/')) return d;
  }
  return null;
}
