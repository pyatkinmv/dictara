import 'dart:convert';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

/// The classic download trick: a link to a Blob URL, clicked from code.
Future<void> saveText(String fileName, String text) async {
  final blob = web.Blob([utf8.encode(text).toJS].toJS, web.BlobPropertyBag(type: 'text/plain;charset=utf-8'));
  final url = web.URL.createObjectURL(blob);
  final link = web.HTMLAnchorElement()
    ..href = url
    ..download = fileName;
  web.document.body!.append(link);
  link.click();
  link.remove();
  web.URL.revokeObjectURL(url);
}
