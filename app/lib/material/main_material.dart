import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import '../app.dart';
import '../data/auth_api.dart';
import '../data/gateway_client.dart';
import '../data/http_transcription_repository.dart';
import '../data/profile_api.dart';
import '../services/token_store.dart';
import '../state/auth_controller.dart';
import '../state/profile_controller.dart';
import '../widgets/error_reporter.dart';

void main() {
  // One client for the data, the login and the profile panel: a login sets its bearer token, a 401 on any
  // request logs out.
  final client = GatewayClient();
  final auth = AuthController(HttpAuthApi(client), const BrowserTokenStore(), onToken: client.setToken);
  client.onUnauthorized = auth.logout;
  final profile = ProfileController(HttpProfileApi(client), auth);

  final errors = ErrorReporter(GlobalKey<ScaffoldMessengerState>());
  // What no screen handled itself (a failed rename, a refused tag) ends up here and is shown in a snack bar.
  // In the browser such an error is not passed to PlatformDispatcher.onError, only to the zone.
  runZonedGuarded(() {
    WidgetsFlutterBinding.ensureInitialized();
    usePathUrlStrategy(); // /tags instead of /#/tags; nginx already falls back to index.html

    runApp(DictaraApp(
      // The page's own origin under /api: nginx forwards it to the gateway in production, the dev server's proxy
      // does the same locally (`web_dev_config.yaml`).
      repository: HttpTranscriptionRepository(client),
      authController: auth,
      profileController: profile,
      // The list follows the progress of files that are still being transcribed.
      pollInterval: const Duration(seconds: 3),
      errorReporter: errors,
    ));
  }, errors.report);
}
