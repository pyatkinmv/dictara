import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/upload.dart';
import '../services/multipart_uploader.dart';

/// The gateway did not answer, or answered with an error.
class GatewayException implements Exception {
  const GatewayException(this.message, {this.statusCode});

  final String message;

  /// The HTTP status, or null if there was no answer at all.
  final int? statusCode;

  @override
  String toString() => 'GatewayException(${statusCode ?? 'no response'}): $message';
}

/// JSON over HTTP to the gateway.
///
/// Requests go to the page's own origin under [baseUrl] (`/api`): in production nginx forwards that to the
/// gateway and cuts the prefix; in development `web_dev_config.yaml` makes Flutter's dev server do the same,
/// so the browser never sees a second origin and no CORS is needed.
class GatewayClient {
  GatewayClient({
    http.Client? httpClient,
    this.baseUrl = '/api',
    this.timeout = const Duration(seconds: 20),
    MultipartUploader uploader = const BrowserUploader(),
  })  : _uploader = uploader,
        _http = httpClient ?? http.Client();

  final http.Client _http;
  final MultipartUploader _uploader;
  final String baseUrl;
  final Duration timeout;

  /// GET [path] (e.g. `/transcriptions`) and decode the JSON body.
  Future<Object?> getJson(String path) async => _decode(await _send(() => _http.get(Uri.parse('$baseUrl$path'))));

  /// GET [path] and return the body as text (UTF-8, whatever the server says the charset is).
  Future<String> getText(String path) async =>
      utf8.decode((await _send(() => _http.get(Uri.parse('$baseUrl$path')))).bodyBytes);

  /// PATCH [path] with [body] as JSON; the answer is not read (the gateway sends 204).
  Future<void> patchJson(String path, Object body) async {
    await _send(() => _http.patch(
          Uri.parse('$baseUrl$path'),
          headers: const {'Content-Type': 'application/json'},
          body: jsonEncode(body),
        ));
  }

  /// POST [body] as JSON to [path] and decode the JSON answer.
  Future<Object?> postJson(String path, Object body) async => _decode(await _send(() => _http.post(
        Uri.parse('$baseUrl$path'),
        headers: const {'Content-Type': 'application/json'},
        body: jsonEncode(body),
      )));

  /// PUT [body] as JSON to [path] and decode the JSON answer.
  Future<Object?> putJson(String path, Object body) async => _decode(await _send(() => _http.put(
        Uri.parse('$baseUrl$path'),
        headers: const {'Content-Type': 'application/json'},
        body: jsonEncode(body),
      )));

  /// DELETE [path]; the answer, if there is one, is decoded (`null` for the gateway's 204).
  Future<Object?> deleteJson(String path) async => _decode(await _send(() => _http.delete(Uri.parse('$baseUrl$path'))));

  /// POST [file] and [fields] to [path] as a multipart form and decode the JSON answer. A file can take minutes
  /// to go out, so unlike other requests this has no time limit; [onProgress] says how far it is, and
  /// [cancellation] stops it (the call then throws a [GatewayException]).
  Future<Object?> postFile(
    String path, {
    required Map<String, String> fields,
    required ChosenFile file,
    void Function(double fraction)? onProgress,
    UploadCancellation? cancellation,
  }) async {
    final UploadReply reply;
    try {
      reply = await _uploader.post(
        '$baseUrl$path',
        fields: fields,
        file: file,
        onProgress: onProgress,
        cancellation: cancellation,
      );
    } on UploadInterrupted catch (e) {
      throw GatewayException(e.message);
    }
    if (reply.status < 200 || reply.status >= 300) {
      throw GatewayException(messageOf(reply.body, fallback: 'HTTP ${reply.status}'), statusCode: reply.status);
    }
    if (reply.body.isEmpty) return null;
    try {
      return jsonDecode(reply.body);
    } on FormatException catch (e) {
      throw GatewayException('The server sent something that is not JSON: ${e.message}', statusCode: reply.status);
    }
  }


  Future<http.Response> _send(Future<http.Response> Function() request) async {
    final http.Response response;
    try {
      response = await request().timeout(timeout);
    } on Exception catch (e) {
      // No connection, a refused proxy target, a timeout: all "could not reach it".
      throw GatewayException('Could not reach the server: $e');
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw GatewayException(_errorMessage(response), statusCode: response.statusCode);
    }
    return response;
  }

  static Object? _decode(http.Response response) {
    if (response.body.isEmpty) return null;
    try {
      return jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException catch (e) {
      throw GatewayException('The server sent something that is not JSON: ${e.message}',
          statusCode: response.statusCode);
    }
  }

  /// Spring's error body carries a `message`; fall back to the status line.
  static String _errorMessage(http.Response response) =>
      messageOf(utf8.decode(response.bodyBytes), fallback: response.reasonPhrase ?? 'HTTP ${response.statusCode}');

  /// The `message` of an error body ([body] is JSON), or [fallback] when there is none.
  static String messageOf(String body, {required String fallback}) {
    try {
      final json = jsonDecode(body);
      if (json is Map && json['message'] is String && (json['message'] as String).isNotEmpty) {
        return json['message'] as String;
      }
    } on FormatException {
      // Not JSON; use the fallback below.
    }
    return fallback;
  }

  void close() => _http.close();
}
