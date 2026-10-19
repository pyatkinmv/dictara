import 'dart:async';

/// Waits for several requests that were started together. When some fail, the first failure is thrown as it was
/// (a `GatewayException` with the gateway's own message, say), not wrapped in the `ParallelWaitError` that a plain
/// `(a, b).wait` throws: what the app shows the user for a failed request is that message, and the screens that
/// used to await these one after another saw exactly this error.
Future<(A, B)> waitBoth<A, B>(Future<A> a, Future<B> b) async {
  try {
    return await (a, b).wait;
  } on ParallelWaitError<(A?, B?), (AsyncError?, AsyncError?)> catch (e) {
    final first = e.errors.$1 ?? e.errors.$2!;
    Error.throwWithStackTrace(first.error, first.stackTrace);
  }
}

/// [waitBoth] for three.
Future<(A, B, C)> waitAll3<A, B, C>(Future<A> a, Future<B> b, Future<C> c) async {
  try {
    return await (a, b, c).wait;
  } on ParallelWaitError<(A?, B?, C?), (AsyncError?, AsyncError?, AsyncError?)> catch (e) {
    final first = e.errors.$1 ?? e.errors.$2 ?? e.errors.$3!;
    Error.throwWithStackTrace(first.error, first.stackTrace);
  }
}
