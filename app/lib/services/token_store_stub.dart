import 'token_store.dart';

/// Outside the browser there is no `localStorage` to use; `BrowserTokenStore` is not exercised on the Dart VM
/// (tests give `AuthController` a `TokenStore` of their own — see `test/support/in_memory_token_store.dart`).
StoredToken? read() => throw UnsupportedError('BrowserTokenStore needs the browser');
void write(StoredToken token) => throw UnsupportedError('BrowserTokenStore needs the browser');
void clear() => throw UnsupportedError('BrowserTokenStore needs the browser');
