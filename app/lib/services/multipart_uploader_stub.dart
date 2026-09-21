import '../models/upload.dart';
import 'multipart_uploader.dart';

/// Outside the browser there is no request to make.
Future<UploadReply> postMultipart(
  String url, {
  required Map<String, String> fields,
  required ChosenFile file,
  void Function(double fraction)? onProgress,
  UploadCancellation? cancellation,
}) =>
    throw UnsupportedError('Uploading needs the browser');
