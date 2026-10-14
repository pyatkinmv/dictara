import 'dart:async';
import 'dart:js_interop';

import 'package:cross_file/cross_file.dart';
import 'package:web/web.dart' as web;

import '../models/upload.dart';

/// Opens the browser's file dialog. The chosen `File` itself travels on in [ChosenFile.source]: nothing of it is
/// read into memory here.
Future<ChosenFile?> pickFile(List<String> extensions) {
  final done = Completer<ChosenFile?>();
  final input = web.HTMLInputElement()
    ..type = 'file'
    ..accept = extensions.map((e) => '.$e').join(',');
  input.onchange = (web.Event _) {
    final file = input.files?.item(0);
    if (!done.isCompleted) {
      done.complete(file == null ? null : ChosenFile(name: file.name, sizeBytes: file.size, source: file));
    }
  }.toJS;
  // Closing the dialog without choosing (supported by current browsers).
  input.oncancel = (web.Event _) {
    if (!done.isCompleted) done.complete(null);
  }.toJS;
  input.click();
  return done.future;
}

/// A dropped file is a `blob:` address of the file the browser holds; fetching it gives the `Blob` without
/// copying the content.
Future<ChosenFile> fromDropped(XFile file) async {
  final response = await web.window.fetch(file.path.toJS).toDart;
  final blob = await response.blob().toDart;
  return ChosenFile(name: file.name, sizeBytes: blob.size, source: blob);
}
