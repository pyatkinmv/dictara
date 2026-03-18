import 'dart:convert';
import 'dart:typed_data';
import 'package:http/http.dart' as http;
import 'models.dart';

class ApiClient {
  static const _base = '/api';

  String? _token;

  void setToken(String? token) => _token = token;

  Map<String, String> get _authHeaders =>
      _token != null ? {'Authorization': 'Bearer $_token'} : {};

  Future<bool> checkHealth() async {
    try {
      final res = await http.get(Uri.parse('$_base/health')).timeout(const Duration(seconds: 5));
      if (res.statusCode == 200) {
        final body = jsonDecode(res.body) as Map<String, dynamic>;
        return body['status'] == 'ok';
      }
    } catch (_) {}
    return false;
  }

  Future<String> submitJob({
    required Uint8List fileBytes,
    required String fileName,
    required String model,
    required String language,
    required bool diarize,
    int? numSpeakers,
    String summaryMode = 'auto',
  }) async {
    final uri = Uri.parse('$_base/transcribe').replace(queryParameters: {
      'model': model,
      if (language != 'auto') 'language': language,
      'diarize': diarize.toString(),
      if (numSpeakers != null) 'num_speakers': numSpeakers.toString(),
      'summary_mode': summaryMode,
    });

    final req = http.MultipartRequest('POST', uri)
      ..headers.addAll(_authHeaders)
      ..files.add(http.MultipartFile.fromBytes('file', fileBytes, filename: fileName));

    final streamed = await req.send();
    final res = await http.Response.fromStream(streamed);

    if (res.statusCode != 202) {
      String msg;
      try {
        final body = jsonDecode(res.body) as Map<String, dynamic>;
        msg = (body['message'] as String?) ?? 'Submit failed: ${res.statusCode}';
      } catch (_) {
        msg = 'Submit failed: ${res.statusCode}';
      }
      throw Exception(msg);
    }

    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return body['job_id'] as String;
  }

  Future<List<String>> fetchSupportedExtensions() async {
    try {
      final res = await http.get(Uri.parse('$_base/formats')).timeout(const Duration(seconds: 5));
      if (res.statusCode == 200) {
        final body = jsonDecode(res.body) as Map<String, dynamic>;
        return List<String>.from(body['extensions'] as List);
      }
    } catch (_) {}
    // fallback — must match gateway's SUPPORTED_EXTENSIONS
    return ['avi', 'flac', 'm4a', 'mkv', 'mov', 'mp3', 'mp4', 'oga', 'ogg', 'opus', 'wav', 'webm'];
  }

  Future<JobResult> pollJob(String jobId) async {
    final res = await http.get(Uri.parse('$_base/jobs/$jobId'), headers: _authHeaders);
    if (res.statusCode != 200) {
      throw Exception('Poll failed: ${res.statusCode}');
    }
    return JobResult.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<List<HistoryItem>> fetchTranscriptions() async {
    final res = await http.get(Uri.parse('$_base/transcriptions'), headers: _authHeaders);
    if (res.statusCode == 401) return [];
    if (res.statusCode != 200) throw Exception('Failed to load history: ${res.statusCode}');
    final list = jsonDecode(res.body) as List;
    return list.map((e) => HistoryItem.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<String>> addTag(String jobId, String tag) async {
    final res = await http.post(
      Uri.parse('$_base/jobs/$jobId/tags'),
      headers: {'Content-Type': 'application/json', ..._authHeaders},
      body: jsonEncode({'tag': tag}),
    );
    if (res.statusCode != 200) throw Exception('Failed to add tag: ${res.statusCode}');
    return (jsonDecode(res.body)['tags'] as List).cast<String>();
  }

  Future<List<String>> removeTag(String jobId, String tag) async {
    final res = await http.delete(
      Uri.parse('$_base/jobs/$jobId/tags/${Uri.encodeComponent(tag)}'),
      headers: _authHeaders,
    );
    if (res.statusCode != 200) throw Exception('Failed to remove tag: ${res.statusCode}');
    return (jsonDecode(res.body)['tags'] as List).cast<String>();
  }

  Future<Map<String, dynamic>> loginByUsername(String username) async {
    final res = await http.post(
      Uri.parse('$_base/auth/login-by-username'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'telegram_username': username}),
    );
    if (res.statusCode != 200) {
      throw Exception('Failed to start login: ${res.statusCode}');
    }
    return jsonDecode(res.body) as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> pollLoginLink(String token) async {
    final res = await http.get(Uri.parse('$_base/auth/login-link/$token'));
    if (res.statusCode == 410) {
      String reason = 'expired';
      try {
        final body = jsonDecode(res.body) as Map<String, dynamic>;
        if ((body['message'] as String?)?.toLowerCase().contains('reject') == true) {
          reason = 'rejected';
        }
      } catch (_) {}
      throw Exception('gone:$reason');
    }
    if (res.statusCode != 200) throw Exception('Poll failed: ${res.statusCode}');
    return jsonDecode(res.body) as Map<String, dynamic>;
  }
}
