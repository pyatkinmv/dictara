/// Formats the upload dialog accepts, lowercase, without the dot.
const uploadExtensions = ['mp3', 'mp4', 'm4a', 'ogg', 'wav', 'flac'];

/// Largest file the dialog takes (the design says "max 400 MB").
const maxUploadBytes = 400 * 1024 * 1024;

/// A file the user picked to upload.
///
/// The mock does not need the content; the real upload will (the gateway takes
/// a multipart body and puts it into GCS), so this gets a byte stream then.
class ChosenFile {
  const ChosenFile({required this.name, required this.sizeBytes});

  final String name;
  final int sizeBytes;

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
  auto('Auto-detect'),
  english('English'),
  russian('Russian');

  const UploadLanguage(this.label);
  final String label;
}

/// "Summary" of the upload dialog: how long the generated summary should be.
enum SummaryMode {
  auto('Auto'),
  short('Short'),
  full('Full'),
  none('None');

  const SummaryMode(this.label);
  final String label;
}

/// "Speakers" of the upload dialog: how many voices to look for.
enum SpeakerCount {
  auto('Auto-detect', null),
  one('1', 1),
  two('2', 2),
  three('3', 3),
  fourPlus('4+', 4);

  const SpeakerCount(this.label, this.expected);
  final String label;

  /// Number handed to the diarizer; null = let it decide.
  final int? expected;
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
