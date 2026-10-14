import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import '../theme/app_palette.dart';

/// Lines of a list: `* `, `- `, `+ ` or `1. `. A list may follow a paragraph line directly.
final _listItem = RegExp(r'^\s*([*+-]|\d+[.)])\s');

/// The summarizer writes plain lines one under another ("• a", "• b"), which Markdown would run together
/// into one paragraph. So that the text shows the way it was written, a single line break becomes a hard
/// one (two spaces at the end of the line). Blank lines and lists are left alone.
String withHardBreaks(String text) {
  final lines = text.replaceAll('\r\n', '\n').split('\n');
  final out = StringBuffer();
  for (var i = 0; i < lines.length; i++) {
    final line = lines[i];
    out.write(line);
    if (i == lines.length - 1) break;
    final next = lines[i + 1];
    final breaks = line.trim().isNotEmpty && next.trim().isNotEmpty && !_listItem.hasMatch(next) && !line.endsWith('  ');
    out.write(breaks ? '  \n' : '\n');
  }
  return out.toString();
}

/// The AI summary: Markdown, in the text style of the Overview tab.
class SummaryMarkdown extends StatelessWidget {
  const SummaryMarkdown({super.key, required this.data});

  final String data;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final body = TextStyle(fontSize: 13.5, height: 1.7, color: p.text2);
    return MarkdownBody(
      data: withHardBreaks(data),
      // The card around it is a SelectionArea already.
      selectable: false,
      styleSheet: MarkdownStyleSheet(
        p: body,
        pPadding: const EdgeInsets.only(bottom: 6),
        strong: body.copyWith(fontWeight: FontWeight.w700, color: p.text),
        em: body.copyWith(fontStyle: FontStyle.italic),
        listBullet: body.copyWith(color: p.accent),
        listIndent: 16,
        listBulletPadding: EdgeInsets.zero,
        blockSpacing: 6,
        h1: body.copyWith(fontSize: 16, fontWeight: FontWeight.w700, color: p.text),
        h2: body.copyWith(fontSize: 15, fontWeight: FontWeight.w700, color: p.text),
        h3: body.copyWith(fontSize: 14, fontWeight: FontWeight.w700, color: p.text),
        code: body.copyWith(fontFamily: 'monospace', fontSize: 12.5, backgroundColor: p.background),
        a: body.copyWith(color: p.accent),
      ),
    );
  }
}
