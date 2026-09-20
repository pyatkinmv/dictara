import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/models/upload.dart';
import 'package:dictara_web/services/file_chooser.dart';

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
}
