import 'package:flutter/widgets.dart';

/// Below this window width the app uses its phone layout: the sidebar becomes a drawer, pages stack their columns.
/// At or above it everything is as on the desktop, so wide screens are not touched by the phone rules.
const mobileBreakpoint = 700.0;

/// True when the window is narrower than [mobileBreakpoint].
bool isMobileWidth(BuildContext context) => MediaQuery.sizeOf(context).width < mobileBreakpoint;
