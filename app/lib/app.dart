import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'data/fake_transcription_repository.dart';
import 'data/transcription_repository.dart';
import 'router.dart';
import 'services/file_chooser.dart';
import 'state/speaker_library_controller.dart';
import 'state/tag_library_controller.dart';
import 'state/transcription_detail_controller.dart';
import 'state/transcription_list_controller.dart';
import 'theme/app_palette.dart';
import 'theme/app_theme.dart';

class DictaraApp extends StatefulWidget {
  const DictaraApp({
    super.key,
    this.palette = AppPalette.teal,
    this.initialLocation = '/transcriptions',
    this.repository,
    this.fileChooser = const BrowserFileChooser(),
    this.pollInterval,
  });

  final AppPalette palette;
  final String initialLocation;

  /// Where data comes from; the mock one until the gateway is wired in.
  final TranscriptionRepository? repository;

  /// How files are picked in the Upload dialog.
  final FileChooser fileChooser;

  /// How often the list refreshes while something is being transcribed; null = never.
  final Duration? pollInterval;

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
        Provider<FileChooser>.value(value: widget.fileChooser),
        ChangeNotifierProvider(
          create: (context) => TranscriptionListController(
            context.read<TranscriptionRepository>(),
            pollInterval: widget.pollInterval,
          ),
        ),
        ChangeNotifierProvider(
          create: (context) => TagLibraryController(
            context.read<TranscriptionRepository>(),
            // The list filter offers the library, so it must follow changes.
            onChanged: context.read<TranscriptionListController>().reload,
          ),
        ),
        ChangeNotifierProvider(
          create: (context) => SpeakerLibraryController(context.read<TranscriptionRepository>()),
        ),
        ChangeNotifierProvider(
          create: (context) => TranscriptionDetailController(
            context.read<TranscriptionRepository>(),
            // A rename must show up in the list too.
            onChanged: context.read<TranscriptionListController>().reload,
          ),
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
