//
// AUTO-GENERATED FILE, DO NOT MODIFY!
//
// @dart=2.18

// ignore_for_file: unused_element, unused_import
// ignore_for_file: always_put_required_named_parameters_first
// ignore_for_file: constant_identifier_names
// ignore_for_file: lines_longer_than_80_chars

part of openapi.api;

class ProgressResponse {
  /// Returns a new [ProgressResponse] instance.
  ProgressResponse({
    required this.phase,
    this.processedS,
    this.totalS,
    this.diarizeProgress,
  });

  String phase;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  double? processedS;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  double? totalS;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  double? diarizeProgress;

  @override
  bool operator ==(Object other) => identical(this, other) || other is ProgressResponse &&
    other.phase == phase &&
    other.processedS == processedS &&
    other.totalS == totalS &&
    other.diarizeProgress == diarizeProgress;

  @override
  int get hashCode =>
    // ignore: unnecessary_parenthesis
    (phase.hashCode) +
    (processedS == null ? 0 : processedS!.hashCode) +
    (totalS == null ? 0 : totalS!.hashCode) +
    (diarizeProgress == null ? 0 : diarizeProgress!.hashCode);

  @override
  String toString() => 'ProgressResponse[phase=$phase, processedS=$processedS, totalS=$totalS, diarizeProgress=$diarizeProgress]';

  Map<String, dynamic> toJson() {
    final json = <String, dynamic>{};
      json[r'phase'] = this.phase;
    if (this.processedS != null) {
      json[r'processed_s'] = this.processedS;
    } else {
      json[r'processed_s'] = null;
    }
    if (this.totalS != null) {
      json[r'total_s'] = this.totalS;
    } else {
      json[r'total_s'] = null;
    }
    if (this.diarizeProgress != null) {
      json[r'diarize_progress'] = this.diarizeProgress;
    } else {
      json[r'diarize_progress'] = null;
    }
    return json;
  }

  /// Returns a new [ProgressResponse] instance and imports its values from
  /// [value] if it's a [Map], null otherwise.
  // ignore: prefer_constructors_over_static_methods
  static ProgressResponse? fromJson(dynamic value) {
    if (value is Map) {
      final json = value.cast<String, dynamic>();

      // Ensure that the map contains the required keys.
      // Note 1: the values aren't checked for validity beyond being non-null.
      // Note 2: this code is stripped in release mode!
      assert(() {
        assert(json.containsKey(r'phase'), 'Required key "ProgressResponse[phase]" is missing from JSON.');
        assert(json[r'phase'] != null, 'Required key "ProgressResponse[phase]" has a null value in JSON.');
        return true;
      }());

      return ProgressResponse(
        phase: mapValueOfType<String>(json, r'phase')!,
        processedS: mapValueOfType<double>(json, r'processed_s'),
        totalS: mapValueOfType<double>(json, r'total_s'),
        diarizeProgress: mapValueOfType<double>(json, r'diarize_progress'),
      );
    }
    return null;
  }

  static List<ProgressResponse> listFromJson(dynamic json, {bool growable = false,}) {
    final result = <ProgressResponse>[];
    if (json is List && json.isNotEmpty) {
      for (final row in json) {
        final value = ProgressResponse.fromJson(row);
        if (value != null) {
          result.add(value);
        }
      }
    }
    return result.toList(growable: growable);
  }

  static Map<String, ProgressResponse> mapFromJson(dynamic json) {
    final map = <String, ProgressResponse>{};
    if (json is Map && json.isNotEmpty) {
      json = json.cast<String, dynamic>(); // ignore: parameter_assignments
      for (final entry in json.entries) {
        final value = ProgressResponse.fromJson(entry.value);
        if (value != null) {
          map[entry.key] = value;
        }
      }
    }
    return map;
  }

  // maps a json object with a list of ProgressResponse-objects as value to a dart map
  static Map<String, List<ProgressResponse>> mapListFromJson(dynamic json, {bool growable = false,}) {
    final map = <String, List<ProgressResponse>>{};
    if (json is Map && json.isNotEmpty) {
      // ignore: parameter_assignments
      json = json.cast<String, dynamic>();
      for (final entry in json.entries) {
        map[entry.key] = ProgressResponse.listFromJson(entry.value, growable: growable,);
      }
    }
    return map;
  }

  /// The list of required keys that must be present in a JSON.
  static const requiredKeys = <String>{
    'phase',
  };
}

