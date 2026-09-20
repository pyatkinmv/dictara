import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'data/fake_transcription_repository.dart';
import 'data/transcription_repository.dart';
import 'router.dart';
import 'state/transcription_list_controller.dart';
import 'theme/app_palette.dart';
import 'theme/app_theme.dart';

class DictaraApp extends StatefulWidget {
  const DictaraApp({
    super.key,
    this.palette = AppPalette.teal,
    this.initialLocation = '/transcriptions',
    this.repository,
  });

  final AppPalette palette;
  final String initialLocation;

  /// Where data comes from; the mock one until the gateway is wired in.
  final TranscriptionRepository? repository;

  @override
  State<DictaraApp> createState() => _DictaraAppState();
}

class _DictaraAppState extends State<DictaraApp> {
  late final GoRouter _router = createRouter(initialLocation: widget.initialLocation);
  late final TranscriptionRepository _repository = widget.repository ?? FakeTranscriptionRepository();

  @override
  void dispose() {
    _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        Provider<TranscriptionRepository>.value(value: _repository),
        ChangeNotifierProvider(
          create: (context) => TranscriptionListController(context.read<TranscriptionRepository>()),
        ),
      ],
      child: MaterialApp.router(
        title: 'Dictara',
        debugShowCheckedModeBanner: false,
        theme: buildAppTheme(widget.palette),
        routerConfig: _router,
      ),
    );
  }
}
