//
// AUTO-GENERATED FILE, DO NOT MODIFY!
//
// @dart=2.18

// ignore_for_file: unused_element, unused_import
// ignore_for_file: always_put_required_named_parameters_first
// ignore_for_file: constant_identifier_names
// ignore_for_file: lines_longer_than_80_chars

part of openapi.api;

class SegmentResponse {
  /// Returns a new [SegmentResponse] instance.
  SegmentResponse({
    required this.start,
    required this.end,
    required this.text,
    this.speaker,
  });

  double start;

  double end;

  String text;

  ///
  /// Please note: This property should have been non-nullable! Since the specification file
  /// does not include a default value (using the "default:" property), however, the generated
  /// source code must fall back to having a nullable type.
  /// Consider adding a "default:" property in the specification file to hide this note.
  ///
  String? speaker;

  @override
  bool operator ==(Object other) => identical(this, other) || other is SegmentResponse &&
    other.start == start &&
    other.end == end &&
    other.text == text &&
    other.speaker == speaker;

  @override
  int get hashCode =>
    // ignore: unnecessary_parenthesis
    (start.hashCode) +
    (end.hashCode) +
    (text.hashCode) +
    (speaker == null ? 0 : speaker!.hashCode);

  @override
  String toString() => 'SegmentResponse[start=$start, end=$end, text=$text, speaker=$speaker]';

  Map<String, dynamic> toJson() {
    final json = <String, dynamic>{};
      json[r'start'] = this.start;
      json[r'end'] = this.end;
      json[r'text'] = this.text;
    if (this.speaker != null) {
      json[r'speaker'] = this.speaker;
    } else {
      json[r'speaker'] = null;
    }
    return json;
  }

  /// Returns a new [SegmentResponse] instance and imports its values from
  /// [value] if it's a [Map], null otherwise.
  // ignore: prefer_constructors_over_static_methods
  static SegmentResponse? fromJson(dynamic value) {
    if (value is Map) {
      final json = value.cast<String, dynamic>();

      // Ensure that the map contains the required keys.
      // Note 1: the values aren't checked for validity beyond being non-null.
      // Note 2: this code is stripped in release mode!
      assert(() {
        assert(json.containsKey(r'start'), 'Required key "SegmentResponse[start]" is missing from JSON.');
        assert(json[r'start'] != null, 'Required key "SegmentResponse[start]" has a null value in JSON.');
        assert(json.containsKey(r'end'), 'Required key "SegmentResponse[end]" is missing from JSON.');
        assert(json[r'end'] != null, 'Required key "SegmentResponse[end]" has a null value in JSON.');
        assert(json.containsKey(r'text'), 'Required key "SegmentResponse[text]" is missing from JSON.');
        assert(json[r'text'] != null, 'Required key "SegmentResponse[text]" has a null value in JSON.');
        return true;
      }());

      return SegmentResponse(
        start: mapValueOfType<double>(json, r'start')!,
        end: mapValueOfType<double>(json, r'end')!,
        text: mapValueOfType<String>(json, r'text')!,
        speaker: mapValueOfType<String>(json, r'speaker'),
      );
    }
    return null;
  }

  static List<SegmentResponse> listFromJson(dynamic json, {bool growable = false,}) {
    final result = <SegmentResponse>[];
    if (json is List && json.isNotEmpty) {
      for (final row in json) {
        final value = SegmentResponse.fromJson(row);
        if (value != null) {
          result.add(value);
        }
      }
    }
    return result.toList(growable: growable);
  }

  static Map<String, SegmentResponse> mapFromJson(dynamic json) {
    final map = <String, SegmentResponse>{};
    if (json is Map && json.isNotEmpty) {
      json = json.cast<String, dynamic>(); // ignore: parameter_assignments
      for (final entry in json.entries) {
        final value = SegmentResponse.fromJson(entry.value);
        if (value != null) {
          map[entry.key] = value;
        }
      }
    }
    return map;
  }

  // maps a json object with a list of SegmentResponse-objects as value to a dart map
  static Map<String, List<SegmentResponse>> mapListFromJson(dynamic json, {bool growable = false,}) {
    final map = <String, List<SegmentResponse>>{};
    if (json is Map && json.isNotEmpty) {
      // ignore: parameter_assignments
      json = json.cast<String, dynamic>();
      for (final entry in json.entries) {
        map[entry.key] = SegmentResponse.listFromJson(entry.value, growable: growable,);
      }
    }
    return map;
  }

  /// The list of required keys that must be present in a JSON.
  static const requiredKeys = <String>{
    'start',
    'end',
    'text',
  };
}

