//
// AUTO-GENERATED FILE, DO NOT MODIFY!
//
// @dart=2.18

// ignore_for_file: unused_element, unused_import
// ignore_for_file: always_put_required_named_parameters_first
// ignore_for_file: constant_identifier_names
// ignore_for_file: lines_longer_than_80_chars

part of openapi.api;


class TranscribeControllerApi {
  TranscribeControllerApi([ApiClient? apiClient]) : apiClient = apiClient ?? defaultApiClient;

  final ApiClient apiClient;

  /// Performs an HTTP 'GET /jobs/{jobId}' operation and returns the [Response].
  /// Parameters:
  ///
  /// * [String] jobId (required):
  Future<Response> getJobWithHttpInfo(String jobId,) async {
    // ignore: prefer_const_declarations
    final path = r'/jobs/{jobId}'
      .replaceAll('{jobId}', jobId);

    // ignore: prefer_final_locals
    Object? postBody;

    final queryParams = <QueryParam>[];
    final headerParams = <String, String>{};
    final formParams = <String, String>{};

    const contentTypes = <String>[];


    return apiClient.invokeAPI(
      path,
      'GET',
      queryParams,
      postBody,
      headerParams,
      formParams,
      contentTypes.isEmpty ? null : contentTypes.first,
    );
  }

  /// Parameters:
  ///
  /// * [String] jobId (required):
  Future<JobResponse?> getJob(String jobId,) async {
    final response = await getJobWithHttpInfo(jobId,);
    if (response.statusCode >= HttpStatus.badRequest) {
      throw ApiException(response.statusCode, await _decodeBodyBytes(response));
    }
    // When a remote server returns no body with a status of 204, we shall not decode it.
    // At the time of writing this, `dart:convert` will throw an "Unexpected end of input"
    // FormatException when trying to decode an empty string.
    if (response.body.isNotEmpty && response.statusCode != HttpStatus.noContent) {
      return await apiClient.deserializeAsync(await _decodeBodyBytes(response), 'JobResponse',) as JobResponse;
    
    }
    return null;
  }

  /// Performs an HTTP 'GET /health' operation and returns the [Response].
  Future<Response> healthWithHttpInfo() async {
    // ignore: prefer_const_declarations
    final path = r'/health';

    // ignore: prefer_final_locals
    Object? postBody;

    final queryParams = <QueryParam>[];
    final headerParams = <String, String>{};
    final formParams = <String, String>{};

    const contentTypes = <String>[];


    return apiClient.invokeAPI(
      path,
      'GET',
      queryParams,
      postBody,
      headerParams,
      formParams,
      contentTypes.isEmpty ? null : contentTypes.first,
    );
  }

  Future<Map<String, String>?> health() async {
    final response = await healthWithHttpInfo();
    if (response.statusCode >= HttpStatus.badRequest) {
      throw ApiException(response.statusCode, await _decodeBodyBytes(response));
    }
    // When a remote server returns no body with a status of 204, we shall not decode it.
    // At the time of writing this, `dart:convert` will throw an "Unexpected end of input"
    // FormatException when trying to decode an empty string.
    if (response.body.isNotEmpty && response.statusCode != HttpStatus.noContent) {
      return Map<String, String>.from(await apiClient.deserializeAsync(await _decodeBodyBytes(response), 'Map<String, String>'),);

    }
    return null;
  }

  /// Performs an HTTP 'POST /transcribe' operation and returns the [Response].
  /// Parameters:
  ///
  /// * [String] model:
  ///
  /// * [String] language:
  ///
  /// * [bool] diarize:
  ///
  /// * [int] numSpeakers:
  ///
  /// * [String] summaryMode:
  ///
  /// * [TranscribeRequest] transcribeRequest:
  Future<Response> transcribeWithHttpInfo({ String? model, String? language, bool? diarize, int? numSpeakers, String? summaryMode, TranscribeRequest? transcribeRequest, }) async {
    // ignore: prefer_const_declarations
    final path = r'/transcribe';

    // ignore: prefer_final_locals
    Object? postBody = transcribeRequest;

    final queryParams = <QueryParam>[];
    final headerParams = <String, String>{};
    final formParams = <String, String>{};

    if (model != null) {
      queryParams.addAll(_queryParams('', 'model', model));
    }
    if (language != null) {
      queryParams.addAll(_queryParams('', 'language', language));
    }
    if (diarize != null) {
      queryParams.addAll(_queryParams('', 'diarize', diarize));
    }
    if (numSpeakers != null) {
      queryParams.addAll(_queryParams('', 'num_speakers', numSpeakers));
    }
    if (summaryMode != null) {
      queryParams.addAll(_queryParams('', 'summary_mode', summaryMode));
    }

    const contentTypes = <String>['application/json'];


    return apiClient.invokeAPI(
      path,
      'POST',
      queryParams,
      postBody,
      headerParams,
      formParams,
      contentTypes.isEmpty ? null : contentTypes.first,
    );
  }

  /// Parameters:
  ///
  /// * [String] model:
  ///
  /// * [String] language:
  ///
  /// * [bool] diarize:
  ///
  /// * [int] numSpeakers:
  ///
  /// * [String] summaryMode:
  ///
  /// * [TranscribeRequest] transcribeRequest:
  Future<SubmitResponse?> transcribe({ String? model, String? language, bool? diarize, int? numSpeakers, String? summaryMode, TranscribeRequest? transcribeRequest, }) async {
    final response = await transcribeWithHttpInfo( model: model, language: language, diarize: diarize, numSpeakers: numSpeakers, summaryMode: summaryMode, transcribeRequest: transcribeRequest, );
    if (response.statusCode >= HttpStatus.badRequest) {
      throw ApiException(response.statusCode, await _decodeBodyBytes(response));
    }
    // When a remote server returns no body with a status of 204, we shall not decode it.
    // At the time of writing this, `dart:convert` will throw an "Unexpected end of input"
    // FormatException when trying to decode an empty string.
    if (response.body.isNotEmpty && response.statusCode != HttpStatus.noContent) {
      return await apiClient.deserializeAsync(await _decodeBodyBytes(response), 'SubmitResponse',) as SubmitResponse;
    
    }
    return null;
  }
}
