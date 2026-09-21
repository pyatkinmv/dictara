import 'package:cross_file/cross_file.dart';

import '../models/upload.dart';
import 'file_chooser_stub.dart' if (dart.library.js_interop) 'file_chooser_web.dart' as impl;

/// Lets the user pick an audio or video file. An interface so that tests can
/// pick "files" without a browser dialog.
abstract class FileChooser {
  /// Null if the user closed the dialog without choosing.
  Future<ChosenFile?> choose();

  /// A file dragged onto the dialog, as the chosen file it becomes.
  Future<ChosenFile> fromDropped(XFile file);
}

/// The browser's file dialog (an `<input type="file">`), so that the file can be sent as it is, however large.
class BrowserFileChooser implements FileChooser {
  const BrowserFileChooser();

  @override
  Future<ChosenFile?> choose() => impl.pickFile(uploadExtensions);

  @override
  Future<ChosenFile> fromDropped(XFile file) => impl.fromDropped(file);
}
