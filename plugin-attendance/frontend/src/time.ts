const timeFormat = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });

export function formatTime(iso: string): string {
  return timeFormat.format(new Date(iso));
}

export function formatDuration(ms: number): string {
  const minutes = Math.floor(Math.max(0, ms) / 60_000);
  if (minutes < 1) {
    return 'меньше минуты';
  }
  if (minutes < 60) {
    return `${minutes} мин`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} ч` : `${hours} ч ${rest} мин`;
}

/** Перерыв короче минуты — «в 14:05 на 12 с», длиннее — «14:05–14:20». */
export function formatGap(from: string, to: string): string {
  const seconds = Math.max(1, Math.round((Date.parse(to) - Date.parse(from)) / 1000));
  return seconds < 60 ? `в ${formatTime(from)} на ${seconds} с` : `${formatTime(from)}–${formatTime(to)}`;
}

/** forms: для 1, для 2–4, для 5 и больше: plural(3, ['раз', 'раза', 'раз']). */
export function plural(count: number, forms: [string, string, string]): string {
  const mod10 = count % 10;
  const mod100 = count % 100;
  if (mod10 === 1 && mod100 !== 11) {
    return forms[0];
  }
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
    return forms[1];
  }
  return forms[2];
}
