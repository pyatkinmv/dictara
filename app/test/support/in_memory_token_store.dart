import 'package:dictara_web/services/token_store.dart';

/// A [TokenStore] that lives only in memory, for tests. Seed it to start already "signed in" (the same thing
/// a returning visitor's saved session does).
class InMemoryTokenStore implements TokenStore {
  InMemoryTokenStore([this._stored]);

  StoredToken? _stored;

  @override
  StoredToken? read() => _stored;

  @override
  void write(StoredToken token) => _stored = token;

  @override
  void clear() => _stored = null;
}
