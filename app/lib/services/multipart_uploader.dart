import '../models/upload.dart';
import 'multipart_uploader_stub.dart' if (dart.library.js_interop) 'multipart_uploader_web.dart' as impl;

/// What the server answered to an upload: the HTTP status and the body as text.
class UploadReply {
  const UploadReply(this.status, this.body);

  final int status;
  final String body;
}

/// Sends a chosen file and some form fields as one `multipart/form-data` request, telling how far it got. An
/// interface so that tests can send "files" without a browser.
abstract class MultipartUploader {
  /// [onProgress] gets a fraction from 0 to 1 while the body goes out. Cancelling [cancellation] stops the
  /// request; the returned future then fails. A refusal from the server is a normal [UploadReply], not an error;
  /// no answer at all (no connection, cancelled) is an exception.
  Future<UploadReply> post(
    String url, {
    required Map<String, String> fields,
    required ChosenFile file,
    Map<String, String> headers = const {},
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  });
}

/// The browser's own `XMLHttpRequest`: the file is passed on as it is (no copy in memory, however large) and the
/// request reports how much has gone out, which `package:http` cannot do in the browser.
class BrowserUploader implements MultipartUploader {
  const BrowserUploader();

  @override
  Future<UploadReply> post(
    String url, {
    required Map<String, String> fields,
    required ChosenFile file,
    Map<String, String> headers = const {},
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  }) =>
      impl.postMultipart(url, fields: fields, file: file, headers: headers, onProgress: onProgress, cancellation: cancellation);
}

/// The request never got an answer: no connection, or it was stopped.
class UploadInterrupted implements Exception {
  const UploadInterrupted(this.message);

  final String message;

  @override
  String toString() => message;
}
