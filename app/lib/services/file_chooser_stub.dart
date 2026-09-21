import 'package:cross_file/cross_file.dart';

import '../models/upload.dart';

/// Outside the browser there is no file dialog; what is dropped keeps its name and size, and cannot be sent.
Future<ChosenFile?> pickFile(List<String> extensions) => throw UnsupportedError('Picking a file needs the browser');

Future<ChosenFile> fromDropped(XFile file) async => ChosenFile(name: file.name, sizeBytes: await file.length());
