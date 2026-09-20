import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'router.dart';
import 'theme/app_palette.dart';
import 'theme/app_theme.dart';

class DictaraApp extends StatefulWidget {
  const DictaraApp({
    super.key,
    this.palette = AppPalette.teal,
    this.initialLocation = '/transcriptions',
  });

  final AppPalette palette;
  final String initialLocation;

  @override
  State<DictaraApp> createState() => _DictaraAppState();
}

class _DictaraAppState extends State<DictaraApp> {
  late final GoRouter _router = createRouter(initialLocation: widget.initialLocation);

  @override
  void dispose() {
    _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'Dictara',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(widget.palette),
      routerConfig: _router,
    );
  }
}
