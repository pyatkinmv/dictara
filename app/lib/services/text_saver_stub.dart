/// Outside the browser there is no download to trigger.
Future<void> saveText(String fileName, String text) => throw UnsupportedError('Saving a file needs the browser');
