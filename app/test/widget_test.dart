import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';

import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  testWidgets('shell shows sidebar navigation and topbar title', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(const DictaraApp());
    await settle(tester);

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
    await settle(tester);

    await tester.tap(find.text('Tags'));
    await settle(tester);

    expect(find.text('Tags'), findsNWidgets(2));
    expect(find.text('Transcriptions'), findsOneWidget);
  });

  testWidgets('profile menu opens and closes', (tester) async {
    useDesktopSize(tester);
    await tester.pumpWidget(const DictaraApp());
    await settle(tester);

    expect(find.text('Sign out'), findsNothing);

    await tester.tap(find.text('Maxim P.'));
    await settle(tester);
    expect(find.text('Sign out'), findsOneWidget);
    expect(find.text('pyatkinmv@gmail.com'), findsOneWidget);

    // Tapping outside closes it.
    await tester.tapAt(const Offset(800, 400));
    await settle(tester);
    expect(find.text('Sign out'), findsNothing);
  });
}
