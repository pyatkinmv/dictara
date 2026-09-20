import 'package:go_router/go_router.dart';

import 'pages/placeholder_page.dart';
import 'shell/app_shell.dart';
import 'shell/nav_destinations.dart';

GoRouter createRouter({String initialLocation = '/transcriptions'}) {
  return GoRouter(
    initialLocation: initialLocation,
    redirect: (context, state) => state.uri.path == '/' ? '/transcriptions' : null,
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(location: state.uri.path, child: child),
        routes: [
          for (final d in navDestinations)
            GoRoute(
              path: d.path,
              // Switching sections is instant in the design, no slide animation.
              pageBuilder: (context, state) => NoTransitionPage(
                key: state.pageKey,
                child: PlaceholderPage(label: d.label),
              ),
            ),
        ],
      ),
    ],
  );
}
