//
// AUTO-GENERATED FILE, DO NOT MODIFY!
//
// @dart=2.18

// ignore_for_file: unused_element, unused_import
// ignore_for_file: always_put_required_named_parameters_first
// ignore_for_file: constant_identifier_names
// ignore_for_file: lines_longer_than_80_chars

part of openapi.api;

class SubmitResponse {
  /// Returns a new [SubmitResponse] instance.
  SubmitResponse({
    required this.jobId,
  });

  String jobId;

  @override
  bool operator ==(Object other) => identical(this, other) || other is SubmitResponse &&
    other.jobId == jobId;

  @override
  int get hashCode =>
    // ignore: unnecessary_parenthesis
    (jobId.hashCode);

  @override
  String toString() => 'SubmitResponse[jobId=$jobId]';

  Map<String, dynamic> toJson() {
    final json = <String, dynamic>{};
      json[r'job_id'] = this.jobId;
    return json;
  }

  /// Returns a new [SubmitResponse] instance and imports its values from
  /// [value] if it's a [Map], null otherwise.
  // ignore: prefer_constructors_over_static_methods
  static SubmitResponse? fromJson(dynamic value) {
    if (value is Map) {
      final json = value.cast<String, dynamic>();

      // Ensure that the map contains the required keys.
      // Note 1: the values aren't checked for validity beyond being non-null.
      // Note 2: this code is stripped in release mode!
      assert(() {
        assert(json.containsKey(r'job_id'), 'Required key "SubmitResponse[job_id]" is missing from JSON.');
        assert(json[r'job_id'] != null, 'Required key "SubmitResponse[job_id]" has a null value in JSON.');
        return true;
      }());

      return SubmitResponse(
        jobId: mapValueOfType<String>(json, r'job_id')!,
      );
    }
    return null;
  }

  static List<SubmitResponse> listFromJson(dynamic json, {bool growable = false,}) {
    final result = <SubmitResponse>[];
    if (json is List && json.isNotEmpty) {
      for (final row in json) {
        final value = SubmitResponse.fromJson(row);
        if (value != null) {
          result.add(value);
        }
      }
    }
    return result.toList(growable: growable);
  }

  static Map<String, SubmitResponse> mapFromJson(dynamic json) {
    final map = <String, SubmitResponse>{};
    if (json is Map && json.isNotEmpty) {
      json = json.cast<String, dynamic>(); // ignore: parameter_assignments
      for (final entry in json.entries) {
        final value = SubmitResponse.fromJson(entry.value);
        if (value != null) {
          map[entry.key] = value;
        }
      }
    }
    return map;
  }

  // maps a json object with a list of SubmitResponse-objects as value to a dart map
  static Map<String, List<SubmitResponse>> mapListFromJson(dynamic json, {bool growable = false,}) {
    final map = <String, List<SubmitResponse>>{};
    if (json is Map && json.isNotEmpty) {
      // ignore: parameter_assignments
      json = json.cast<String, dynamic>();
      for (final entry in json.entries) {
        map[entry.key] = SubmitResponse.listFromJson(entry.value, growable: growable,);
      }
    }
    return map;
  }

  /// The list of required keys that must be present in a JSON.
  static const requiredKeys = <String>{
    'job_id',
  };
}

