import 'token_store_stub.dart' if (dart.library.js_interop) 'token_store_web.dart' as impl;

/// A signed-in session, as kept between page loads.
class StoredToken {
  const StoredToken({required this.token, this.displayName});

  final String token;
  final String? displayName;
}

/// Where the session survives a page reload. An interface so tests can see or seed it without a browser.
abstract class TokenStore {
  StoredToken? read();
  void write(StoredToken token);
  void clear();
}

/// The browser's `localStorage` — the same thing every other page reload survives.
class BrowserTokenStore implements TokenStore {
  const BrowserTokenStore();

  @override
  StoredToken? read() => impl.read();

  @override
  void write(StoredToken token) => impl.write(token);

  @override
  void clear() => impl.clear();
}
