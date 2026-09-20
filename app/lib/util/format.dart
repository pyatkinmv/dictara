/// "14 min", "1h 12min" — the duration style used in the design.
String formatDuration(Duration d) {
  final minutes = d.inMinutes;
  if (minutes < 60) return '$minutes min';
  return '${minutes ~/ 60}h ${minutes % 60}min';
}

/// "0:34", "12:05" — a transcript timestamp.
String formatTimestamp(Duration d) {
  final seconds = d.inSeconds % 60;
  return '${d.inMinutes}:${seconds.toString().padLeft(2, '0')}';
}

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// "22 Jun".
String formatDay(DateTime d) => '${d.day} ${_months[d.month - 1]}';
