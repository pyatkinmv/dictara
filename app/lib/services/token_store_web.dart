import 'package:web/web.dart' as web;

import 'token_store.dart';

const _tokenKey = 'dictara_jwt';
const _nameKey = 'dictara_display_name';

/// A blocked or unavailable `localStorage` (a private window, cleared site data) means no saved session, not a
/// crash; the user just signs in again.
StoredToken? read() {
  try {
    final token = web.window.localStorage.getItem(_tokenKey);
    if (token == null || token.isEmpty) return null;
    final name = web.window.localStorage.getItem(_nameKey);
    return StoredToken(token: token, displayName: (name == null || name.isEmpty) ? null : name);
  } catch (_) {
    return null;
  }
}

void write(StoredToken token) {
  try {
    web.window.localStorage.setItem(_tokenKey, token.token);
    web.window.localStorage.setItem(_nameKey, token.displayName ?? '');
  } catch (_) {
    // Nothing to fall back to; the session just does not survive a reload this time.
  }
}

void clear() {
  try {
    web.window.localStorage.removeItem(_tokenKey);
    web.window.localStorage.removeItem(_nameKey);
  } catch (_) {}
}
