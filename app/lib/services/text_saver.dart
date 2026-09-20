import 'text_saver_stub.dart' if (dart.library.js_interop) 'text_saver_web.dart' as impl;

/// Hands a text to the user as a file. An interface so that tests can see what would be saved without a
/// browser download.
abstract class TextSaver {
  Future<void> save(String fileName, String text);
}

/// The browser's download: the text becomes a file the user finds in their downloads.
class BrowserTextSaver implements TextSaver {
  const BrowserTextSaver();

  @override
  Future<void> save(String fileName, String text) => impl.saveText(fileName, text);
}
