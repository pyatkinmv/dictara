import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import 'pages/detail/transcription_detail_page.dart';
import 'pages/placeholder_page.dart';
import 'pages/speakers_page.dart';
import 'pages/tags_page.dart';
import 'pages/transcriptions_page.dart';
import 'shell/app_shell.dart';
import 'shell/nav_destinations.dart';

GoRouter createRouter({String initialLocation = '/transcriptions'}) {
  // Switching sections is instant in the design, no slide animation.
  NoTransitionPage<void> page(GoRouterState state, Widget child) {
    return NoTransitionPage(key: state.pageKey, child: child);
  }

  return GoRouter(
    initialLocation: initialLocation,
    redirect: (context, state) => state.uri.path == '/' ? '/transcriptions' : null,
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(location: state.uri.path, child: child),
        routes: [
          GoRoute(
            path: '/transcriptions',
            pageBuilder: (context, state) => page(state, const TranscriptionsPage()),
            routes: [
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => page(
                  state,
                  TranscriptionDetailPage(id: int.tryParse(state.pathParameters['id'] ?? '')),
                ),
              ),
            ],
          ),
          GoRoute(path: '/tags', pageBuilder: (context, state) => page(state, const TagsPage())),
          GoRoute(path: '/speakers', pageBuilder: (context, state) => page(state, const SpeakersPage())),
          for (final d in navDestinations.where((d) => d.path != '/transcriptions' && d.path != '/tags' && d.path != '/speakers'))
            GoRoute(
              path: d.path,
              pageBuilder: (context, state) => page(state, PlaceholderPage(label: d.label)),
            ),
        ],
      ),
    ],
  );
}
