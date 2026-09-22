import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'data/transcription_repository.dart';
import 'pages/login/login_overlay.dart';
import 'router.dart';
import 'services/file_chooser.dart';
import 'services/text_saver.dart';
import 'state/auth_controller.dart';
import 'state/speaker_library_controller.dart';
import 'state/tag_library_controller.dart';
import 'state/transcription_detail_controller.dart';
import 'state/transcription_list_controller.dart';
import 'theme/app_palette.dart';
import 'theme/app_theme.dart';
import 'widgets/error_reporter.dart';

class DictaraApp extends StatefulWidget {
  const DictaraApp({
    super.key,
    this.palette = AppPalette.teal,
    this.initialLocation = '/transcriptions',
    required this.repository,
    required this.authController,
    this.fileChooser = const BrowserFileChooser(),
    this.textSaver = const BrowserTextSaver(),
    this.pollInterval,
    this.errorReporter,
  });

  final AppPalette palette;
  final String initialLocation;

  /// Where data comes from.
  final TranscriptionRepository repository;

  /// The signed-in session; also blocks the app behind [LoginOverlay] while there is none.
  final AuthController authController;

  /// How files are picked in the Upload dialog.
  final FileChooser fileChooser;

  /// How the ".txt" button of the detail page hands the transcript over.
  final TextSaver textSaver;

  /// How often the list refreshes while something is being transcribed; null = never.
  final Duration? pollInterval;

  /// Tells the user about an error that no screen handled (a failed rename, a refused tag). `main` hands the same
  /// reporter to the zone the app runs in, which passes such errors to it; without one the app makes its own,
  /// which only shows what is reported to it (tests).
  final ErrorReporter? errorReporter;

  @override
  State<DictaraApp> createState() => _DictaraAppState();
}

class _DictaraAppState extends State<DictaraApp> {
  late final GoRouter _router = createRouter(initialLocation: widget.initialLocation);
  late final TranscriptionRepository _repository = widget.repository;
  late final ErrorReporter _errors = widget.errorReporter ?? ErrorReporter(GlobalKey<ScaffoldMessengerState>());

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
        ChangeNotifierProvider<AuthController>.value(value: widget.authController),
        Provider<FileChooser>.value(value: widget.fileChooser),
        Provider<TextSaver>.value(value: widget.textSaver),
        Provider<ErrorReporter>.value(value: _errors),
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
        scaffoldMessengerKey: _errors.messengerKey,
        theme: buildAppTheme(widget.palette),
        routerConfig: _router,
        // On top of every route: nothing in the app works without a session, so this is not itself a route
        // (no back button would make sense) and it never depends on where the router is. A fresh Overlay is
        // needed here (not just a Stack) because the router's own Overlay lives inside `child`, one level too
        // deep for the login card's TextField to reach as an ancestor.
        builder: (context, child) => Overlay(
          initialEntries: [
            OverlayEntry(
              builder: (context) => Stack(
                children: [
                  ?child,
                  Consumer<AuthController>(
                    builder: (context, auth, _) => auth.isLoggedIn ? const SizedBox.shrink() : const LoginOverlay(),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
