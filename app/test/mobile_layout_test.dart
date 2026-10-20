import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';

import 'support/fake_transcription_repository.dart';
import 'test_utils.dart';

/// A typical phone, portrait.
void usePhoneSize(WidgetTester tester) {
  tester.view.physicalSize = const Size(375, 812);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  Future<void> open(WidgetTester tester, String location, {bool phone = true}) async {
    phone ? usePhoneSize(tester) : useDesktopSize(tester);
    await tester.pumpWidget(DictaraApp(
      authController: signedInAuthController(),
      repository: FakeTranscriptionRepository(),
      initialLocation: location,
    ));
    await settle(tester);
  }

  group('phone layout', () {
    testWidgets('the sidebar is out of the way: a menu button, and the page and Upload have the whole width', (tester) async {
      await open(tester, '/transcriptions');

      expect(find.byKey(const Key('open-menu')), findsOneWidget);
      expect(find.text('Usage this month'), findsNothing, reason: 'the sidebar is hidden until the menu is opened');
      expect(find.text('Upload'), findsOneWidget);
      expect(tester.getTopLeft(find.text('Transcriptions').first).dx, lessThan(120));
      expect(tester.takeException(), isNull);
    });

    testWidgets('the menu button opens the sidebar, choosing a section goes there and closes it', (tester) async {
      await open(tester, '/transcriptions');

      await tester.tap(find.byKey(const Key('open-menu')));
      await settle(tester);
      expect(find.text('Speakers'), findsOneWidget);

      await tester.tap(find.text('Speakers'));
      await settle(tester);
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('Speakers'), findsWidgets);
      expect(find.byKey(const Key('open-menu')), findsOneWidget);
      expect(find.text('Tags'), findsNothing, reason: 'the drawer closed again');
      expect(tester.takeException(), isNull);
    });

    for (final location in ['/transcriptions', '/transcriptions/0', '/tags', '/speakers', '/settings', '/settings/mcp-access', '/settings/profile']) {
      testWidgets('$location fits a 375 px screen without overflowing', (tester) async {
        await open(tester, location);
        expect(tester.takeException(), isNull);
      });
    }

    for (final tab in ['Overview', 'Transcript']) {
      testWidgets('the $tab tab of a transcription fits a 375 px screen', (tester) async {
        await open(tester, '/transcriptions/0');
        await tester.tap(find.text(tab));
        await settle(tester);
        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('the Upload dialog fits a 375 px screen, its options one under another', (tester) async {
      await open(tester, '/transcriptions');
      await tester.tap(find.text('Upload'));
      await settle(tester);

      final language = tester.getTopLeft(find.text('LANGUAGE'));
      final summary = tester.getTopLeft(find.text('SUMMARY'));
      expect(summary.dy, greaterThan(language.dy));
      expect(summary.dx, language.dx);
      expect(tester.takeException(), isNull);
    });

    testWidgets('on a transcription the .txt and Copy buttons sit under the title, leaving it the whole width', (tester) async {
      await open(tester, '/transcriptions/0');

      final title = tester.getRect(find.text('Quarterly Review Meeting').last);
      expect(tester.getTopLeft(find.text('.txt')).dy, greaterThan(title.bottom));
      expect(tester.getTopLeft(find.text('.txt')).dx, lessThan(100));
      expect(tester.takeException(), isNull);
    });

    testWidgets('the Overview tab puts the file facts under the summary', (tester) async {
      await open(tester, '/transcriptions/0');
      await tester.tap(find.text('Overview'));
      await settle(tester);

      expect(tester.getTopLeft(find.text('DURATION')).dy, greaterThan(tester.getTopLeft(find.text('SUMMARY')).dy));
      expect(tester.getTopLeft(find.text('DURATION')).dx, lessThan(375));
      expect(tester.takeException(), isNull);
    });
  });

  group('desktop layout is unchanged', () {
    testWidgets('the sidebar is always there next to the page, and there is no menu button', (tester) async {
      await open(tester, '/transcriptions', phone: false);

      expect(find.byKey(const Key('open-menu')), findsNothing);
      expect(find.byType(Drawer), findsNothing);
      expect(find.text('Speakers'), findsOneWidget);
      expect(tester.getTopLeft(find.text('Dictara')).dx, lessThan(100));
      expect(tester.getTopLeft(find.text('Upload')).dx, greaterThan(1000));
      expect(tester.takeException(), isNull);
    });
  });
}
