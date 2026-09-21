import 'package:flutter/material.dart';

import '../data/gateway_client.dart';

/// Tells the user, in a snack bar at the bottom of the window, that something they did did not work.
///
/// It is the catch-all for actions nobody handled themselves (renaming, tag and speaker changes, accepting a
/// suggestion...): the message is the one the gateway sent ("A tag with this name already exists"), or a
/// plain sentence when there was no answer. Screens that show their own error (the Upload dialog) do not come here.
class ErrorReporter {
  ErrorReporter(this.messengerKey, {DateTime Function() now = DateTime.now}) : _now = now;

  final GlobalKey<ScaffoldMessengerState> messengerKey;
  final DateTime Function() _now;

  String? _lastMessage;
  DateTime? _lastShownAt;

  /// The same message twice in a row within this time is shown once: a server that is down fails every
  /// request, and a stack of identical bars helps nobody.
  static const repeatWindow = Duration(seconds: 8);

  static const genericMessage = 'Something went wrong. Please try again.';

  /// What the user reads for [error]: the gateway's own words when it refused something ("A tag with this name
  /// already exists"), a plain sentence when the trouble is the connection or the server (whose details, such as
  /// the text of a parse error, are for the console).
  static String messageFor(Object error) {
    if (error is! GatewayException) return genericMessage;
    final message = error.message.trim();
    final status = error.statusCode;
    if (message.startsWith('Could not reach the server')) {
      return 'Could not reach the server. Check your connection and try again.';
    }
    if (message.contains('not JSON') || (status != null && status >= 500)) {
      return 'The server had a problem${status == null ? '' : ' (HTTP $status)'}. Please try again in a moment.';
    }
    return message.isEmpty ? genericMessage : message;
  }

  void report(Object error, [StackTrace? stack]) {
    debugPrint('Unhandled error: $error${stack == null ? '' : '\n$stack'}');
    final messenger = messengerKey.currentState;
    if (messenger == null) return;
    final message = messageFor(error);
    final now = _now();
    if (message == _lastMessage && _lastShownAt != null && now.difference(_lastShownAt!) < repeatWindow) return;
    _lastMessage = message;
    _lastShownAt = now;
    messenger
      ..clearSnackBars()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          behavior: SnackBarBehavior.floating,
          width: 440,
          showCloseIcon: true,
          duration: const Duration(seconds: 6),
        ),
      );
  }
}
