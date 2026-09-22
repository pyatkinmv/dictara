import 'package:cross_file/cross_file.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/models/upload.dart';
import 'package:dictara_web/services/file_chooser.dart';
import 'package:dictara_web/services/text_saver.dart';
import 'package:dictara_web/services/token_store.dart';
import 'package:dictara_web/state/auth_controller.dart';

import 'support/fake_auth_api.dart';
import 'support/in_memory_token_store.dart';

/// Loads the bundled Inter font. Without it tests render text with a wide
/// placeholder font, which produces false overflow errors.
Future<void> loadInter() async {
  final loader = FontLoader('Inter');
  for (final w in ['Regular', 'Medium', 'SemiBold', 'Bold', 'ExtraBold']) {
    loader.addFont(rootBundle.load('assets/fonts/Inter-$w.ttf'));
  }
  await loader.load();
}

/// The design targets a wide browser window.
void useDesktopSize(WidgetTester tester) {
  tester.view.physicalSize = const Size(1280, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// Lets futures, route changes and short animations finish. Not
/// `pumpAndSettle`: the list page has an endlessly pulsing progress bar.
Future<void> settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 400));
}

/// Picks whatever [next] holds, without a browser dialog.
class FakeFileChooser implements FileChooser {
  FakeFileChooser([this.next]);

  ChosenFile? next;
  int calls = 0;

  @override
  Future<ChosenFile?> choose() async {
    calls++;
    return next;
  }

  @override
  Future<ChosenFile> fromDropped(XFile file) async => ChosenFile(name: file.name, sizeBytes: await file.length());
}

/// Remembers what would have been downloaded, without a browser.
class FakeTextSaver implements TextSaver {
  final List<({String fileName, String text})> saved = [];

  /// Set to make saving fail.
  Object? error;

  @override
  Future<void> save(String fileName, String text) async {
    if (error != null) throw error!;
    saved.add((fileName: fileName, text: text));
  }
}

/// An `AuthController` that starts already signed in — a saved session was already found, the way a returning
/// visitor's would be. For the many tests that are not about login itself; a test of the login screen builds
/// its own with `InMemoryTokenStore()` (no seed) instead.
AuthController signedInAuthController({String displayName = 'Test User'}) => AuthController(
      FakeAuthApi(),
      InMemoryTokenStore(StoredToken(token: 'test-token', displayName: displayName)),
    );

/// Catches what the app puts on the clipboard, without a platform. Call before the app is shown.
List<String> captureClipboard(WidgetTester tester) {
  final copied = <String>[];
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
    if (call.method == 'Clipboard.setData') copied.add((call.arguments as Map)['text'] as String);
    return null;
  });
  addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
  return copied;
}
