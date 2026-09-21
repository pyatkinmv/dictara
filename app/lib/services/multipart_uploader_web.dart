import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../models/upload.dart';
import 'multipart_uploader.dart';

/// One `XMLHttpRequest` with a `FormData` body holding the fields and the file.
Future<UploadReply> postMultipart(
  String url, {
  required Map<String, String> fields,
  required ChosenFile file,
  void Function(double fraction)? onProgress,
  UploadCancellation? cancellation,
}) {
  final source = file.source.jsify();
  if (source == null || !source.isA<web.Blob>()) {
    return Future.error(StateError('This file was not picked in the browser, so it cannot be sent'));
  }
  final form = web.FormData();
  fields.forEach((name, value) => form.append(name, value.toJS));
  form.append('file', source as web.Blob, file.name);

  final done = Completer<UploadReply>();
  final xhr = web.XMLHttpRequest()..open('POST', url);
  xhr.upload.onprogress = (web.ProgressEvent event) {
    if (event.lengthComputable && event.total > 0) onProgress?.call(event.loaded / event.total);
  }.toJS;
  xhr.onload = (web.Event _) {
    if (!done.isCompleted) done.complete(UploadReply(xhr.status, xhr.responseText));
  }.toJS;
  xhr.onerror = (web.Event _) {
    if (!done.isCompleted) done.completeError(const UploadInterrupted('Could not reach the server'));
  }.toJS;
  xhr.onabort = (web.Event _) {
    if (!done.isCompleted) done.completeError(const UploadInterrupted('The upload was cancelled'));
  }.toJS;
  cancellation?.onCancel = () => xhr.abort();
  xhr.send(form);
  return done.future;
}
