import 'external_link_stub.dart' if (dart.library.js_interop) 'external_link_web.dart' as impl;

/// Opens a URL in a new tab (the "Open @bot in Telegram" link of the login screen). An interface so tests can
/// see what would have opened without a browser.
abstract class ExternalLinkOpener {
  void open(String url);
}

class BrowserExternalLinkOpener implements ExternalLinkOpener {
  const BrowserExternalLinkOpener();

  @override
  void open(String url) => impl.open(url);
}
