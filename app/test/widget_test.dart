import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // Tests render text with a wide placeholder font unless a real one is loaded,
  // which produces false overflow errors. Load the bundled Inter.
  setUpAll(() async {
    final loader = FontLoader('Inter');
    for (final w in ['Regular', 'Medium', 'SemiBold', 'Bold', 'ExtraBold']) {
      loader.addFont(rootBundle.load('assets/fonts/Inter-$w.ttf'));
    }
    await loader.load();
  });

  // The design targets a wide browser window.
  void useDesktopSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

  testWidgets('shell shows sidebar navigation and topbar title', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(const DictaraApp());
    await tester.pumpAndSettle();

    // Sidebar item + topbar title.
    expect(find.text('Transcriptions'), findsNWidgets(2));
    for (final label in ['Threads', 'Tags', 'Speakers', 'Settings']) {
      expect(find.text(label), findsOneWidget);
    }
    expect(find.text('Upload'), findsOneWidget);
  });

  testWidgets('navigating updates the topbar title', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(const DictaraApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Tags'));
    await tester.pumpAndSettle();

    expect(find.text('Tags'), findsNWidgets(2));
    expect(find.text('Transcriptions'), findsOneWidget);
  });

  testWidgets('profile menu opens and closes', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(const DictaraApp());
    await tester.pumpAndSettle();

    expect(find.text('Sign out'), findsNothing);

    await tester.tap(find.text('Maxim P.'));
    await tester.pumpAndSettle();
    expect(find.text('Sign out'), findsOneWidget);
    expect(find.text('pyatkinmv@gmail.com'), findsOneWidget);

    // Tapping outside closes it.
    await tester.tapAt(const Offset(800, 400));
    await tester.pumpAndSettle();
    expect(find.text('Sign out'), findsNothing);
  });
}
