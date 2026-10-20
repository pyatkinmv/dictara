import 'dart:ui' show Size;

import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/app_version.dart';

import 'support/fake_transcription_repository.dart';
import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  testWidgets('shell shows sidebar navigation and topbar title', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
    await settle(tester);

    // Sidebar item + topbar title.
    expect(find.text('Transcriptions'), findsNWidgets(2));
    for (final label in ['Tags', 'Speakers', 'Settings']) {
      expect(find.text(label), findsOneWidget);
    }
    expect(find.text('Threads'), findsNothing); // dropped for good
    expect(find.text('Upload'), findsOneWidget);
  });

  testWidgets('the sidebar shows the frontend version', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
    await settle(tester);

    expect(find.text('v$appVersion'), findsOneWidget);
  });

  testWidgets('navigating updates the topbar title', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
    await settle(tester);

    await tester.tap(find.text('Tags'));
    await settle(tester);

    // Sidebar item, topbar title and the page's own heading.
    expect(find.text('Tags'), findsNWidgets(3));
    expect(find.text('Transcriptions'), findsOneWidget);
  });

  testWidgets('a short window does not overflow the sidebar', (tester) async {
    tester.view.physicalSize = const Size(1280, 300);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
    await settle(tester);

    // A RenderFlex overflow is reported as an exception and would fail the test.
    expect(tester.takeException(), isNull);
    expect(find.text('Settings'), findsOneWidget);
  });

  testWidgets('profile menu opens and closes', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
    await settle(tester);

    expect(find.text('Sign out'), findsNothing);

    await tester.tap(find.text('Test User'));
    await settle(tester);
    expect(find.text('Sign out'), findsOneWidget);

    // Tapping outside closes it.
    await tester.tapAt(const Offset(800, 400));
    await settle(tester);
    expect(find.text('Sign out'), findsNothing);
  });

  testWidgets('Sign out logs out and blocks the app behind the login screen', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
    await settle(tester);

    await tester.tap(find.text('Test User'));
    await settle(tester);
    await tester.tap(find.text('Sign out'));
    await settle(tester);

    expect(find.text('Continue with your Telegram account'), findsOneWidget);
  });
}
