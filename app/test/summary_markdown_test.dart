import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/theme/app_palette.dart';
import 'package:dictara_web/theme/app_theme.dart';
import 'package:dictara_web/widgets/summary_markdown.dart';

import 'test_utils.dart';

Future<void> _show(WidgetTester tester, String markdown) {
  return tester.pumpWidget(
    MaterialApp(
      theme: buildAppTheme(AppPalette.teal),
      home: Scaffold(body: SingleChildScrollView(child: SummaryMarkdown(data: markdown))),
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('withHardBreaks', () {
    test('a single line break becomes a hard one', () {
      expect(withHardBreaks('a\nb\nc'), 'a  \nb  \nc');
    });

    test('blank lines stay paragraph breaks', () {
      expect(withHardBreaks('a\n\nb'), 'a\n\nb');
    });

    test('a list keeps following its lead-in line directly', () {
      expect(withHardBreaks('**Key points**\n* one\n* two\n1. three'), '**Key points**\n* one\n* two\n1. three');
    });

    test('a line that is already a hard break is not doubled, and Windows line ends are normalised', () {
      expect(withHardBreaks('a  \nb\r\nc'), 'a  \nb  \nc');
    });

    test('an empty text stays empty', () {
      expect(withHardBreaks(''), '');
    });
  });

  group('SummaryMarkdown', () {
    testWidgets('bold, bullets and paragraphs are drawn, not shown as marks', (tester) async {
      await _show(tester, 'Intro paragraph.\n\n**Key points**\n* first\n* second\n\nClosing.');

      expect(find.textContaining('Intro paragraph.'), findsOneWidget);
      expect(find.text('Key points'), findsOneWidget);
      expect(find.text('first'), findsOneWidget);
      expect(find.text('second'), findsOneWidget);
      expect(find.text('•'), findsNWidgets(2));
      expect(find.textContaining('*'), findsNothing);
    });

    testWidgets('plain "•" lines stay on their own lines', (tester) async {
      await _show(tester, 'Follow-ups\n• one\n• two\n• three');

      final rendered = tester.widget<RichText>(find.byType(RichText).first).text.toPlainText();
      expect(rendered, 'Follow-ups\n• one\n• two\n• three');
    });
  });
}
