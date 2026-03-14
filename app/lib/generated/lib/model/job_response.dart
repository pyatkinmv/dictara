//
// AUTO-GENERATED FILE, DO NOT MODIFY!
//
// @dart=2.18

// ignore_for_file: unused_element, unused_import
// ignore_for_file: always_put_required_named_parameters_first
// ignore_for_file: constant_identifier_names
// ignore_for_file: lines_longer_than_80_chars

part of openapi.api;

class JobResponse {
  /// Returns a new [JobResponse] instance.
  JobResponse({
    required this.jobId,
    required this.status,
    this.progress,
    this.result,
    this.durationS,
    this.elapsedS,
    this.error,
  });

  String jobId;

  String status;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  ProgressResponse? progress;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  ResultResponse? result;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  double? durationS;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  double? elapsedS;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  String? error;

  @override
  bool operator ==(Object other) => identical(this, other) || other is JobResponse &&
    other.jobId == jobId &&
    other.status == status &&
    other.progress == progress &&
    other.result == result &&
    other.durationS == durationS &&
    other.elapsedS == elapsedS &&
    other.error == error;

  @override
  int get hashCode =>
    // ignore: unnecessary_parenthesis
    (jobId.hashCode) +
    (status.hashCode) +
    (progress == null ? 0 : progress!.hashCode) +
    (result == null ? 0 : result!.hashCode) +
    (durationS == null ? 0 : durationS!.hashCode) +
    (elapsedS == null ? 0 : elapsedS!.hashCode) +
    (error == null ? 0 : error!.hashCode);

  @override
  String toString() => 'JobResponse[jobId=$jobId, status=$status, progress=$progress, result=$result, durationS=$durationS, elapsedS=$elapsedS, error=$error]';

  Map<String, dynamic> toJson() {
    final json = <String, dynamic>{};
      json[r'job_id'] = this.jobId;
      json[r'status'] = this.status;
    if (this.progress != null) {
      json[r'progress'] = this.progress;
    } else {
      json[r'progress'] = null;
    }
    if (this.result != null) {
      json[r'result'] = this.result;
    } else {
      json[r'result'] = null;
    }
    if (this.durationS != null) {
      json[r'duration_s'] = this.durationS;
    } else {
      json[r'duration_s'] = null;
    }
    if (this.elapsedS != null) {
      json[r'elapsed_s'] = this.elapsedS;
    } else {
      json[r'elapsed_s'] = null;
    }
    if (this.error != null) {
      json[r'error'] = this.error;
    } else {
      json[r'error'] = null;
    }
    return json;
  }

  /// Returns a new [JobResponse] instance and imports its values from
  /// [value] if it's a [Map], null otherwise.
  // ignore: prefer_constructors_over_static_methods
  static JobResponse? fromJson(dynamic value) {
    if (value is Map) {
      final json = value.cast<String, dynamic>();

      // Ensure that the map contains the required keys.
      // Note 1: the values aren't checked for validity beyond being non-null.
      // Note 2: this code is stripped in release mode!
      assert(() {
        assert(json.containsKey(r'job_id'), 'Required key "JobResponse[job_id]" is missing from JSON.');
        assert(json[r'job_id'] != null, 'Required key "JobResponse[job_id]" has a null value in JSON.');
        assert(json.containsKey(r'status'), 'Required key "JobResponse[status]" is missing from JSON.');
        assert(json[r'status'] != null, 'Required key "JobResponse[status]" has a null value in JSON.');
        return true;
      }());

      return JobResponse(
        jobId: mapValueOfType<String>(json, r'job_id')!,
        status: mapValueOfType<String>(json, r'status')!,
        progress: ProgressResponse.fromJson(json[r'progress']),
        result: ResultResponse.fromJson(json[r'result']),
        durationS: mapValueOfType<double>(json, r'duration_s'),
        elapsedS: mapValueOfType<double>(json, r'elapsed_s'),
        error: mapValueOfType<String>(json, r'error'),
      );
    }
    return null;
  }

  static List<JobResponse> listFromJson(dynamic json, {bool growable = false,}) {
    final result = <JobResponse>[];
    if (json is List && json.isNotEmpty) {
      for (final row in json) {
        final value = JobResponse.fromJson(row);
        if (value != null) {
          result.add(value);
        }
      }
    }
    return result.toList(growable: growable);
  }

  static Map<String, JobResponse> mapFromJson(dynamic json) {
    final map = <String, JobResponse>{};
    if (json is Map && json.isNotEmpty) {
      json = json.cast<String, dynamic>(); // ignore: parameter_assignments
      for (final entry in json.entries) {
        final value = JobResponse.fromJson(entry.value);
        if (value != null) {
          map[entry.key] = value;
        }
      }
    }
    return map;
  }

  // maps a json object with a list of JobResponse-objects as value to a dart map
  static Map<String, List<JobResponse>> mapListFromJson(dynamic json, {bool growable = false,}) {
    final map = <String, List<JobResponse>>{};
    if (json is Map && json.isNotEmpty) {
      // ignore: parameter_assignments
      json = json.cast<String, dynamic>();
      for (final entry in json.entries) {
        map[entry.key] = JobResponse.listFromJson(entry.value, growable: growable,);
      }
    }
    return map;
  }

  /// The list of required keys that must be present in a JSON.
  static const requiredKeys = <String>{
    'job_id',
    'status',
  };
}

