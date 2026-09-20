import 'package:file_picker/file_picker.dart';

import '../models/upload.dart';

/// Lets the user pick an audio or video file. An interface so that tests can
/// pick "files" without a browser dialog.
abstract class FileChooser {
  /// Null if the user closed the dialog without choosing.
  Future<ChosenFile?> choose();
}

/// The browser's file dialog, through `file_picker`.
class BrowserFileChooser implements FileChooser {
  const BrowserFileChooser();

  @override
  Future<ChosenFile?> choose() async {
    // No `withData`: the mock upload only needs the name and size.
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: uploadExtensions,
    );
    final file = result?.files.firstOrNull;
    return file == null
        ? null
        : ChosenFile(name: file.name, sizeBytes: file.size);
  }
}
