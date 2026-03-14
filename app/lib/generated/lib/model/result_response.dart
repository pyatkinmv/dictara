//
// AUTO-GENERATED FILE, DO NOT MODIFY!
//
// @dart=2.18

// ignore_for_file: unused_element, unused_import
// ignore_for_file: always_put_required_named_parameters_first
// ignore_for_file: constant_identifier_names
// ignore_for_file: lines_longer_than_80_chars

part of openapi.api;

class ResultResponse {
  /// Returns a new [ResultResponse] instance.
  ResultResponse({
    this.segments = const [],
    required this.formattedText,
    this.summary,
    this.audioDurationS,
  });

  List<SegmentResponse> segments;

  String formattedText;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  String? summary;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  double? audioDurationS;

  @override
  bool operator ==(Object other) => identical(this, other) || other is ResultResponse &&
    _deepEquality.equals(other.segments, segments) &&
    other.formattedText == formattedText &&
    other.summary == summary &&
    other.audioDurationS == audioDurationS;

  @override
  int get hashCode =>
    // ignore: unnecessary_parenthesis
    (segments.hashCode) +
    (formattedText.hashCode) +
    (summary == null ? 0 : summary!.hashCode) +
    (audioDurationS == null ? 0 : audioDurationS!.hashCode);

  @override
  String toString() => 'ResultResponse[segments=$segments, formattedText=$formattedText, summary=$summary, audioDurationS=$audioDurationS]';

  Map<String, dynamic> toJson() {
    final json = <String, dynamic>{};
      json[r'segments'] = this.segments;
      json[r'formatted_text'] = this.formattedText;
    if (this.summary != null) {
      json[r'summary'] = this.summary;
    } else {
      json[r'summary'] = null;
    }
    if (this.audioDurationS != null) {
      json[r'audio_duration_s'] = this.audioDurationS;
    } else {
      json[r'audio_duration_s'] = null;
    }
    return json;
  }

  /// Returns a new [ResultResponse] instance and imports its values from
  /// [value] if it's a [Map], null otherwise.
  // ignore: prefer_constructors_over_static_methods
  static ResultResponse? fromJson(dynamic value) {
    if (value is Map) {
      final json = value.cast<String, dynamic>();

      // Ensure that the map contains the required keys.
      // Note 1: the values aren't checked for validity beyond being non-null.
      // Note 2: this code is stripped in release mode!
      assert(() {
        assert(json.containsKey(r'segments'), 'Required key "ResultResponse[segments]" is missing from JSON.');
        assert(json[r'segments'] != null, 'Required key "ResultResponse[segments]" has a null value in JSON.');
        assert(json.containsKey(r'formatted_text'), 'Required key "ResultResponse[formatted_text]" is missing from JSON.');
        assert(json[r'formatted_text'] != null, 'Required key "ResultResponse[formatted_text]" has a null value in JSON.');
        return true;
      }());

      return ResultResponse(
        segments: SegmentResponse.listFromJson(json[r'segments']),
        formattedText: mapValueOfType<String>(json, r'formatted_text')!,
        summary: mapValueOfType<String>(json, r'summary'),
        audioDurationS: mapValueOfType<double>(json, r'audio_duration_s'),
      );
    }
    return null;
  }

  static List<ResultResponse> listFromJson(dynamic json, {bool growable = false,}) {
    final result = <ResultResponse>[];
    if (json is List && json.isNotEmpty) {
      for (final row in json) {
        final value = ResultResponse.fromJson(row);
        if (value != null) {
          result.add(value);
        }
      }
    }
    return result.toList(growable: growable);
  }

  static Map<String, ResultResponse> mapFromJson(dynamic json) {
    final map = <String, ResultResponse>{};
    if (json is Map && json.isNotEmpty) {
      json = json.cast<String, dynamic>(); // ignore: parameter_assignments
      for (final entry in json.entries) {
        final value = ResultResponse.fromJson(entry.value);
        if (value != null) {
          map[entry.key] = value;
        }
      }
    }
    return map;
  }

  // maps a json object with a list of ResultResponse-objects as value to a dart map
  static Map<String, List<ResultResponse>> mapListFromJson(dynamic json, {bool growable = false,}) {
    final map = <String, List<ResultResponse>>{};
    if (json is Map && json.isNotEmpty) {
      // ignore: parameter_assignments
      json = json.cast<String, dynamic>();
      for (final entry in json.entries) {
        map[entry.key] = ResultResponse.listFromJson(entry.value, growable: growable,);
      }
    }
    return map;
  }

  /// The list of required keys that must be present in a JSON.
  static const requiredKeys = <String>{
    'segments',
    'formatted_text',
  };
}

