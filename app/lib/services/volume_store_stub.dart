/// Outside the browser there is no `localStorage`. Unlike the other browser-only stubs this one does not throw:
/// the volume is a preference, the detail page builds its player on the Dart VM in widget tests, and "not
/// remembered" is the right behavior there.
double? read() => null;
void write(double volume) {}
