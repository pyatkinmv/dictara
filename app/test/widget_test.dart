import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/main.dart';

void main() {
  testWidgets('App renders smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const DictaraApp());
    expect(find.text('Dictara'), findsOneWidget);
  });
}
