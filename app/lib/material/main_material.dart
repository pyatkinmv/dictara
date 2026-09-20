import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import '../app.dart';

void main() {
  usePathUrlStrategy(); // /tags instead of /#/tags; nginx already falls back to index.html
  runApp(const DictaraApp());
}
