/// "14 min", "1h 12min" — the duration style used in the list and header; "<1 min" for anything shorter
/// than a minute (a file of 45 seconds is not "0 min").
String formatDuration(Duration d) {
  final minutes = d.inMinutes;
  if (minutes == 0 && d > Duration.zero) return '<1 min';
  if (minutes < 60) return '$minutes min';
  return '${minutes ~/ 60}h ${minutes % 60}min';
}

/// "0:34", "14:22", "1:02:03" — a transcript timestamp or a clock-style length.
String formatTimestamp(Duration d) {
  String two(int n) => n.toString().padLeft(2, '0');
  final seconds = d.inSeconds % 60;
  if (d.inHours > 0) return '${d.inHours}:${two(d.inMinutes % 60)}:${two(seconds)}';
  return '${d.inMinutes}:${two(seconds)}';
}

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// "22 Jun".
String formatDay(DateTime d) => '${d.day} ${_months[d.month - 1]}';

/// "22 Jun · 14:41".
String formatDayTime(DateTime d) {
  String two(int n) => n.toString().padLeft(2, '0');
  return '${formatDay(d)} · ${two(d.hour)}:${two(d.minute)}';
}

/// "1,840".
String formatCount(int n) {
  final digits = n.abs().toString();
  final buffer = StringBuffer(n < 0 ? '-' : '');
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) buffer.write(',');
    buffer.write(digits[i]);
  }
  return buffer.toString();
}

/// "22.4 MB" (binary units, one decimal).
String formatFileSize(int bytes) {
  const kb = 1024;
  const mb = kb * 1024;
  const gb = mb * 1024;
  if (bytes >= gb) return '${(bytes / gb).toStringAsFixed(1)} GB';
  if (bytes >= mb) return '${(bytes / mb).toStringAsFixed(1)} MB';
  if (bytes >= kb) return '${(bytes / kb).toStringAsFixed(1)} KB';
  return '$bytes B';
}

/// The name of the downloaded transcript: the title with what file systems do not allow replaced by a
/// space, plus `.txt` ("Q2 review: notes/draft" -> "Q2 review notes draft.txt").
String transcriptFileName(String title) {
  final name = title.replaceAll(RegExp(r'[\/:*?"<>|\x00-\x1f]+'), ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
  return '${name.isEmpty ? 'transcript' : name}.txt';
}
