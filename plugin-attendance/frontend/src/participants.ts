/**
 * Подпись участника по его identity: имён в событиях streamapi нет.
 *
 * Meet собирает identity гостя как «<имя>-<15 символов [0-9a-z]>»: имя в нижнем регистре, пробелы
 * и дефисы заменены на «_», всё кроме латиницы и цифр выброшено. Имя без латиницы, например
 * кириллическое, превращается в «participant». У участников с учётной записью identity —
 * идентификатор пользователя, его показываем как есть.
 */
const GUEST_IDENTITY = /^([a-z0-9_]+)-([0-9a-z]{15})$/;

export interface ParticipantLabel {
  name: string;
  /** Начало случайной части identity гостя: различает одинаковые имена. */
  tag: string | null;
  /** Имя ничего не говорит об участнике («Участник»), метку показываем всегда. */
  generic: boolean;
  initials: string;
  hue: number;
}

export function labelFor(participantId: string): ParticipantLabel {
  const hue = hueOf(participantId);
  const match = GUEST_IDENTITY.exec(participantId);
  if (!match) {
    return { name: participantId, tag: null, generic: false, initials: initialsOf(participantId), hue };
  }
  const [, prefix, suffix] = match;
  const tag = suffix.slice(0, 4);
  if (prefix === 'participant') {
    return { name: 'Участник', tag, generic: true, initials: 'У', hue };
  }
  const name = prefix
    .split('_')
    .filter(Boolean)
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
  return { name, tag, generic: false, initials: initialsOf(name), hue };
}

function initialsOf(name: string): string {
  const letters = name
    .split(/[\s_.@-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0])
    .join('');
  return letters.toUpperCase() || '?';
}

/** FNV-1a: похожие identity получают разные цвета. */
function hueOf(value: string): number {
  let hash = 0x811c9dc5;
  for (const char of value) {
    hash ^= char.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0) % 360;
}
