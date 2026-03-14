import 'dart:convert';
import 'dart:typed_data';
import 'package:http/http.dart' as http;
import 'models.dart';

class ApiClient {
  static const _base = '/api';

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
      ..files.add(http.MultipartFile.fromBytes('file', fileBytes, filename: fileName));

    final streamed = await req.send();
    final res = await http.Response.fromStream(streamed);

    if (res.statusCode != 202) {
      throw Exception('Submit failed: ${res.statusCode} ${res.body}');
    }

    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return body['job_id'] as String;
  }

  Future<JobResult> pollJob(String jobId) async {
    final res = await http.get(Uri.parse('$_base/jobs/$jobId'));
    if (res.statusCode != 200) {
      throw Exception('Poll failed: ${res.statusCode}');
    }
    return JobResult.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }
}
