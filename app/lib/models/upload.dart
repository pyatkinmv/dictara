import 'transcription.dart';

/// Formats the upload dialog accepts, lowercase, without the dot.
const uploadExtensions = ['mp3', 'mp4', 'm4a', 'ogg', 'wav', 'flac'];

/// Largest file the dialog takes (the design says "max 400 MB").
const maxUploadBytes = 400 * 1024 * 1024;

/// A file the user picked to upload.
class ChosenFile {
  const ChosenFile({required this.name, required this.sizeBytes, this.source});

  final String name;
  final int sizeBytes;

  /// What the file is read from when it is sent: in the browser the file itself (a `Blob`), handed over
  /// without copying it into memory. Null where nothing is really sent (the mock, tests).
  final Object? source;

  /// Why this file cannot be uploaded, or null if it can.
  String? get problem {
    final dot = name.lastIndexOf('.');
    final extension = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
    if (!uploadExtensions.contains(extension)) {
      return 'Unsupported format. Use ${uploadExtensions.map((e) => e.toUpperCase()).join(', ')}.';
    }
    if (sizeBytes > maxUploadBytes) return 'This file is larger than 400 MB.';
    return null;
  }
}

/// "Language" of the upload dialog.
enum UploadLanguage {
  auto('Auto-detect', 'auto'),
  english('English', 'en'),
  russian('Russian', 'ru');

  const UploadLanguage(this.label, this.code);
  final String label;

  /// What the gateway is told.
  final String code;
}

/// "Summary" of the upload dialog: whether and how long the generated summary should be. The modes are the
/// gateway's own (`off`, `auto`, `brief`, `concise`, `full`).
enum SummaryMode {
  off('Off'),
  auto('Auto'),
  brief('Brief'),
  concise('Concise'),
  full('Full');

  const SummaryMode(this.label);
  final String label;
}

/// "Speakers" of the upload dialog: whether to tell the voices apart, and how many there are.
enum SpeakerCount {
  auto('Auto-detect', null),
  off('Off', null),
  one('1', 1),
  two('2', 2),
  three('3', 3),
  fourPlus('4+', 4);

  const SpeakerCount(this.label, this.expected);
  final String label;

  /// Number handed to the diarizer; null = let it decide (or, with [diarize] false, nothing to decide).
  final int? expected;

  /// Off means one voice, so there is nobody to tell apart and the (slow, costly) diarization is skipped.
  bool get diarize => this != off;
}

/// Everything the upload dialog collects.
class UploadRequest {
  const UploadRequest({
    required this.file,
    this.language = UploadLanguage.auto,
    this.summary = SummaryMode.auto,
    this.speakers = SpeakerCount.auto,
  });

  final ChosenFile file;
  final UploadLanguage language;
  final SummaryMode summary;
  final SpeakerCount speakers;
}

/// What an upload came to: the entry as the list shows it, and whether the gateway already had this file
/// with these options (then nothing is transcribed again and [item] is the existing entry, in whatever
/// state it is).
class UploadOutcome {
  const UploadOutcome(this.item, {this.duplicate = false});

  final Transcription item;
  final bool duplicate;
}

/// Lets the dialog stop an upload that is on its way.
class UploadCancellation {
  void Function()? _onCancel;
  bool _cancelled = false;

  bool get cancelled => _cancelled;

  /// The sender registers how to stop; if the upload was cancelled already it stops at once.
  set onCancel(void Function() stop) {
    _onCancel = stop;
    if (_cancelled) stop();
  }

  void cancel() {
    if (_cancelled) return;
    _cancelled = true;
    _onCancel?.call();
  }
}
